"""
delamain Gateway - query-string redaction for log records (SAF-90).

Credentials must never end up in the gateway's logs, but two loggers write full
request URLs, query string included:

* ``uvicorn.access`` — one line per HTTP request, e.g.
  ``"GET /transfer/status?token=... HTTP/1.1" 400``. Rejecting ``?token=`` with a
  4xx (issue #8) does not help here: the access line is written for the rejected
  request too.
* ``httpx`` — at INFO it logs every outgoing request URL
  (``HTTP Request: GET http://127.0.0.1:8650/...?token=...``), i.e. whatever the
  gateway forwards to the Java backend.

``QueryParamRedactionFilter`` rewrites the URL-ish arguments of a record so that
the *value* of every sensitive URL parameter is replaced by ``***`` — including the
odd spellings that the 400 ``token_in_query_not_allowed`` check does not catch
(``TOKEN=``, ``token[]=``, ``;token=``, ``%74oken=``, repeated parameters; see
``redact_query_params``). The path, the parameter names, their order and every
other parameter stay as they were, so the line is still useful for
troubleshooting.

Scope / where it is wired (all from ``gateway/main.py:main()``):

* ``install_log_redaction()`` puts the filter on the root logger's handlers
  (covers ``httpx`` and every ``delamain.*`` / ``src.*`` logger, which propagate
  to root);
* ``uvicorn_log_config()`` is handed to uvicorn as ``log_config`` and puts the
  filter on the ``uvicorn.access`` logger and uvicorn's ``default`` handler.

Each record passes through the filter exactly once. **This only applies when the
gateway is started through ``main.main()``** (docker/entrypoint.sh,
the ``delamain-gateway`` console script, ``python main.py``). Serving the ASGI app
with a plain ``uvicorn`` command or another launcher bypasses ``log_config`` and
the access log is NOT redacted. Not covered either (follow-ups): the ``fastmcp``
logger's own RichHandler (``propagate=False``), dict-style ``record.args``, and
URLs inside exception tracebacks.

The filter runs synchronously on the (single) event loop for every access-log
line, and request targets are attacker-controlled (up to h11's 16 KB limit), so
every step here is linear in the input length; see the timing test in
tests/test_log_redaction.py.
"""

from __future__ import annotations

import copy
import logging
import re
from typing import Any
from urllib.parse import unquote_plus

# Parameter names (compared case-insensitively after percent-decoding, "-" treated
# as "_", an array suffix such as "[]" ignored) whose values are masked:
#   token, auth_token          - the transfer token (issue #8) / generic bearer token
#   access_token, refresh_token,
#   id_token                   - OAuth 2.0 / OIDC tokens (RFC 6750 §2.3 allows the
#                                access token in the query; refresh tokens are worth
#                                even more)
#   api_key, apikey            - common API-key spellings
#   password, secret,
#   client_secret              - never legitimate in a URL, mask if someone does it
#   sig, signature             - pre-signed URL signatures that grant access by
#                                themselves
# Exact names only: "contains token" matching would hit legitimate names such as
# token_type / max_tokens. Bare ``key`` is intentionally NOT in the list: the Java
# backend's resource routes take ``key=<Android resource name>``
# (ResourceRoutes.java), which is not a secret and is useful in the
# gateway->backend httpx log lines.
SENSITIVE_QUERY_PARAMS = frozenset(
    {
        "token",
        "auth_token",
        "access_token",
        "refresh_token",
        "id_token",
        "api_key",
        "apikey",
        "password",
        "secret",
        "client_secret",
        "sig",
        "signature",
    }
)

MASK = "***"

# Longest sensitive name above is 13 chars; allow generous room for encoding
# ("%74%6F%6B%65%6E" = 15 chars, x3 for triple encoding) and an array suffix. Longer
# names are treated as non-sensitive without decoding them (bounded work per name).
_MAX_NAME_LEN = 128

# "token[]" / "token[0]" (PHP/Rails-style array params) count as "token". The class
# excludes "[" too, so a long run of "[" cannot make the match quadratic (H1).
_ARRAY_SUFFIX_RE = re.compile(r"\[[^\[\]]*\]$")

# Query strings are parsed the way the gateway parses them (Starlette parse_qsl):
# parameters are separated by "&" only, and a parameter's value runs to the next
# "&" — a raw ";" or "#" is part of the value (h11 keeps a raw "#" in the query
# string). Additionally ";" is treated as a name boundary inside a parameter, so
# "a=1;token=x" is caught too (L5); once a sensitive name is found, everything to
# the next "&" is masked, so no tail of the value stays in clear text (L1, M1).

# Matrix parameters inside the path: "/a;token=x/b" (RFC 3986 §3.3 allows ";" in
# segments; some frameworks parse them as parameters). uvicorn's access log
# re-quotes the path (urllib.parse.quote(scope["path"])), so there the same
# parameter shows up as "/a%3Btoken%3Dx/b": both the literal and the
# percent-encoded ";" / "=" are recognised. A sensitive value is masked up to the
# next ";" / "%3B" or the end of the path — NOT just to the next "/", because a
# value sent as "AAA%2FBBB" is logged as "AAA/BBB" (L1).
_MATRIX_PARAM_RE = re.compile(
    r"(;|%3[Bb])"                          # 1: separator
    r"((?:(?!%3[BbDd])[^;/=%]|%(?!3[BbDd]))*)"  # 2: name (no "/", ";", "=", %3B, %3D)
    r"(=|%3[Dd])"                          # 3: equals
    r"((?:(?!%3[Bb])[^;])*)"               # 4: value, up to the next ; / %3B / end
)


def _normalise_name(name: str) -> str:
    # Decode until stable, at most 3 rounds: uvicorn's access log re-quotes the path,
    # so a matrix parameter name the server saw as "%74oken" is logged as
    # "%2574oken"; 3 rounds cover clients that sent 1-3 encoding layers. A 4th layer
    # is not recognised here — neither is it by the server: Starlette decodes a
    # query name exactly once and does not parse matrix parameters at all, and the
    # Java backend never sees a name the gateway did not forward, so such a name is
    # never treated as "token" by anything.
    decoded = name
    for _ in range(3):
        nxt = unquote_plus(decoded)
        if nxt == decoded:
            break
        decoded = nxt
    decoded = decoded.strip().lower().replace("-", "_")
    return _ARRAY_SUFFIX_RE.sub("", decoded).strip()


def _is_sensitive(name: str) -> bool:
    if len(name) > _MAX_NAME_LEN:
        return False
    return _normalise_name(name) in SENSITIVE_QUERY_PARAMS


def _redact_query(query: str) -> str:
    pieces = query.split("&")
    changed = False
    for i, piece in enumerate(pieces):
        if "=" not in piece:
            continue
        offset = 0
        for sub in piece.split(";"):
            name, eq, _ = sub.partition("=")
            if eq and _is_sensitive(name):
                value_start = offset + len(name) + 1
                if value_start < len(piece):  # non-empty value (to the next "&")
                    pieces[i] = piece[:value_start] + MASK
                    changed = True
                break
            offset += len(sub) + 1
    return "&".join(pieces) if changed else query


def _redact_matrix(path: str) -> str:
    def repl(m: re.Match[str]) -> str:
        sep, name, eq, value = m.groups()
        if value and _is_sensitive(name):
            return f"{sep}{name}{eq}{MASK}"
        return m.group(0)

    return _MATRIX_PARAM_RE.sub(repl, path)


def redact_query_params(text: str) -> str:
    """Return ``text`` with the values of sensitive URL parameters replaced by
    ``***``.

    ``text`` is a request target (``/path?a=1&token=x``) or a full URL. Parameter
    names are matched case-insensitively after URL-decoding, with an array suffix
    (``token[]``, ``token[0]``) ignored, so ``TOKEN=``, ``Token=``, ``%74oken=``,
    ``token%5B%5D=`` all match. Covered forms:

    * query parameters (everything after the first ``?``) whose name starts after
      ``&``, ``;`` or the ``?`` — every occurrence of a repeated parameter is masked,
      and the value is masked up to the next ``&``. ``#`` gets no special treatment:
      h11 keeps a raw ``#`` in the query string and Starlette parses parameters
      after it (``?a=1#z&token=x`` *is* a token for the gateway);
    * matrix parameters in the path (``/a;token=x/b``), also in the re-quoted form
      uvicorn's access log prints (``/a%3Btoken%3Dx/b``), masked up to the next
      ``;`` / ``%3B`` or the end of the path.

    Only ``name=value`` pairs with a non-empty value are rewritten (keeping the name
    exactly as it was written). A string with nothing to mask is returned unchanged
    (the same object). Linear in ``len(text)``.
    """
    if "?" not in text and ";" not in text and "%3" not in text:
        return text
    head, qmark, query = text.partition("?")
    new_head = _redact_matrix(head) if (";" in head or "%3" in head) else head
    new_query = _redact_query(query) if "=" in query else query
    if new_head == head and new_query == query:
        return text
    return new_head + qmark + new_query


def _redact_arg(arg: Any) -> Any:
    if isinstance(arg, str):
        return redact_query_params(arg)
    # httpx.URL / starlette.datastructures.URL: format them as their string form
    # (exactly what "%s" would print) so the query can be redacted. Other types
    # are left alone — calling str() on arbitrary objects is not free.
    if type(arg).__name__ == "URL":
        try:
            as_text = str(arg)
        except Exception:
            return arg
        redacted = redact_query_params(as_text)
        return arg if redacted == as_text else redacted
    return arg


class QueryParamRedactionFilter(logging.Filter):
    """logging.Filter that masks sensitive query-parameter values in a record.

    * ``record.args`` (tuple): every ``str``/URL argument is redacted; the tuple
      keeps its length and the position of every element, which uvicorn's
      AccessFormatter relies on (it unpacks exactly five args).
    * ``record.msg`` is redacted only when the record has no args (a pre-formatted
      message such as an f-string). With args, ``msg`` is a format string and
      rewriting it could break %-formatting.

    Never drops a record and never raises: on any unexpected error the record is
    passed through as it is.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            args = record.args
            if isinstance(args, tuple) and args:
                new_args = tuple(_redact_arg(a) for a in args)
                if any(n is not o for n, o in zip(new_args, args)):
                    record.args = new_args
            elif not args and isinstance(record.msg, str):
                record.msg = redact_query_params(record.msg)
        except Exception:  # pragma: no cover - defensive, logging must not fail
            pass
        return True


def _has_redaction_filter(filterer: logging.Filterer) -> bool:
    return any(isinstance(f, QueryParamRedactionFilter) for f in filterer.filters)


def install_log_redaction() -> None:
    """Attach the redaction filter to every handler on the root logger: covers
    ``httpx`` and all ``delamain.*`` / ``src.*`` loggers, which propagate to root.
    Idempotent. ``uvicorn.*`` loggers do not propagate to root; they get the filter
    from ``uvicorn_log_config()`` instead (one filter per record path)."""
    for handler in logging.getLogger().handlers:
        if not _has_redaction_filter(handler):
            handler.addFilter(QueryParamRedactionFilter())


def uvicorn_log_config() -> dict:
    """uvicorn's own default ``LOGGING_CONFIG`` (same handlers, formatters, streams
    and levels) plus the redaction filter on

    * the ``uvicorn.access`` logger (``propagate=False``; one pass per access
      record, whatever handlers are attached), and
    * the ``default`` handler (``uvicorn`` / ``uvicorn.error`` records).

    Passed to uvicorn as ``log_config`` by ``main.main()`` so the filter is part of
    the dictConfig uvicorn applies at server start. Only effective on that launch
    path (see the module docstring)."""
    from uvicorn.config import LOGGING_CONFIG

    config = copy.deepcopy(LOGGING_CONFIG)
    config.setdefault("filters", {})["delamain_redact_query"] = {"()": QueryParamRedactionFilter}
    default_handler = config["handlers"]["default"]
    default_handler["filters"] = [*default_handler.get("filters", []), "delamain_redact_query"]
    access_logger = config["loggers"]["uvicorn.access"]
    access_logger["filters"] = [*access_logger.get("filters", []), "delamain_redact_query"]
    return config
