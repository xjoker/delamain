"""SAF-90: unit tests for gateway/src/log_redaction.py — the query-string
redaction applied to uvicorn's access log and httpx's outgoing-request log.

The end-to-end check against a real uvicorn server started through main.py lives
in test_access_log_redaction_e2e.py.
"""

import http.server
import io
import logging
import sys
import threading
import time

import httpx
import pytest
from uvicorn.logging import AccessFormatter

import main
from src.log_redaction import (
    MASK,
    QueryParamRedactionFilter,
    install_log_redaction,
    redact_query_params,
    uvicorn_log_config,
)

FAKE = "FAKE-SECRET-123"


@pytest.mark.parametrize(
    "raw, expected",
    [
        # the SAF-90 case: token masked, path and other params untouched
        (f"/transfer/status?token={FAKE}&foo=bar", f"/transfer/status?token={MASK}&foo=bar"),
        (f"/transfer/status?foo=bar&token={FAKE}", f"/transfer/status?foo=bar&token={MASK}"),
        # every name in the list, case-insensitive, the written name is kept
        (f"/x?TOKEN={FAKE}", f"/x?TOKEN={MASK}"),
        (f"/x?access_token={FAKE}&a=1", f"/x?access_token={MASK}&a=1"),
        (f"/x?api_key={FAKE}&apikey={FAKE}", f"/x?api_key={MASK}&apikey={MASK}"),
        (f"/x?password={FAKE}", f"/x?password={MASK}"),
        (f"/x?sig={FAKE}&Signature={FAKE}", f"/x?sig={MASK}&Signature={MASK}"),
        (f"/x?client_secret={FAKE}", f"/x?client_secret={MASK}"),
        # percent-encoded / plus-encoded parameter names are still recognised
        (f"/x?%74oken={FAKE}", f"/x?%74oken={MASK}"),
        (f"/x?access%5Ftoken={FAKE}", f"/x?access%5Ftoken={MASK}"),
        # repeated parameter: every occurrence is masked
        (f"/x?token={FAKE}&token=other-{FAKE}", f"/x?token={MASK}&token={MASK}"),
        # full URL (httpx log line)
        (
            f"http://127.0.0.1:8650/transfer/status?token={FAKE}#frag",
            f"http://127.0.0.1:8650/transfer/status?token={MASK}",  # '#' is part of the value (M1)
        ),
        # --- L5 (Elliot, #19 review): case variants and odd forms of the name ---
        (f"/x?Token={FAKE}", f"/x?Token={MASK}"),
        (f"/x?tOkEn={FAKE}&a=1", f"/x?tOkEn={MASK}&a=1"),
        (f"/x?%54OKEN={FAKE}", f"/x?%54OKEN={MASK}"),
        (f"/x?%74%6F%6B%65%6E={FAKE}", f"/x?%74%6F%6B%65%6E={MASK}"),
        (f"/x?token+={FAKE}", f"/x?token+={MASK}"),
        # array-style names
        (f"/x?token[]={FAKE}&a=1", f"/x?token[]={MASK}&a=1"),
        (f"/x?token[0]={FAKE}", f"/x?token[0]={MASK}"),
        (f"/x?token%5B%5D={FAKE}", f"/x?token%5B%5D={MASK}"),
        (f"/x?api_key[]={FAKE}", f"/x?api_key[]={MASK}"),
        # ';' as a name boundary (L5); the value is masked to the next '&', because
        # that is where the server (Starlette parse_qsl) ends it (L1)
        (f"/x?a=1;token={FAKE};b=2", f"/x?a=1;token={MASK}"),
        (f"/x?a=1&b=2;Token={FAKE}&c=3", f"/x?a=1&b=2;Token={MASK}&c=3"),
        # matrix parameters in the path, with or without a query string
        (f"/transfer/status;token={FAKE}", f"/transfer/status;token={MASK}"),
        (f"/transfer/status;token={FAKE}?foo=bar", f"/transfer/status;token={MASK}?foo=bar"),
        (f"/a;v=1;Token={FAKE}/b;x=2", f"/a;v=1;Token={MASK};x=2"),  # value runs past '/' (L1)
        (f"/a;%74oken={FAKE}#frag", f"/a;%74oken={MASK}"),
        # ...and as uvicorn's access log prints them: quote(path) turns ";" / "="
        # into %3B / %3D
        (f"/transfer/status%3Btoken%3D{FAKE}", f"/transfer/status%3Btoken%3D{MASK}"),
        (f"/a%3bv%3d1%3BToken%3D{FAKE}/b?foo=bar", f"/a%3bv%3d1%3BToken%3D{MASK}?foo=bar"),
        # a name sent as "%74oken" in the path is logged double-encoded by uvicorn
        (f"/a%3B%2574oken%3D{FAKE}", f"/a%3B%2574oken%3D{MASK}"),
        (f"/x?%2574oken={FAKE}", f"/x?%2574oken={MASK}"),
        # repeated, mixing every form above
        (
            f"/x?token={FAKE}&Token=b-{FAKE}&token[]=c-{FAKE};%74oken=d-{FAKE}&keep=1",
            f"/x?token={MASK}&Token={MASK}&token[]={MASK}&keep=1",
        ),
        # --- M1: '#' is not a fragment for the gateway (h11 keeps it in the query
        # string, Starlette parses params after it), so no special handling ---
        (f"/transfer/status?a=1#z&token={FAKE}", f"/transfer/status?a=1#z&token={MASK}"),
        (f"/x?token=A#{FAKE}&b=1", f"/x?token={MASK}&b=1"),
        (f"/x?token=A#B{FAKE}", f"/x?token={MASK}"),
        (f"http://127.0.0.1:8650/x?a=1#f&token={FAKE}", f"http://127.0.0.1:8650/x?a=1#f&token={MASK}"),
        # --- L1: the whole value is masked ---
        (f"/x?token=A;{FAKE}&c=1", f"/x?token={MASK}&c=1"),
        (f"/x?token=;{FAKE}", f"/x?token={MASK}"),
        (f"/a;token=AAA/{FAKE}?x=1", f"/a;token={MASK}?x=1"),
        (f"/a%3Btoken%3DAAA/{FAKE}%3Bv%3D1/c", f"/a%3Btoken%3D{MASK}%3Bv%3D1/c"),
        (f"/a;sig=AAA/{FAKE};v=1", f"/a;sig={MASK};v=1"),
        (f"/a;token=/{FAKE}", f"/a;token={MASK}"),
        # --- L5 additions: more names, '-' treated as '_' ---
        (f"/x?refresh_token={FAKE}&id_token={FAKE}", f"/x?refresh_token={MASK}&id_token={MASK}"),
        (f"/x?auth_token={FAKE}&secret={FAKE}&a=1", f"/x?auth_token={MASK}&secret={MASK}&a=1"),
        (f"/x?Access-Token={FAKE}&api-key={FAKE}", f"/x?Access-Token={MASK}&api-key={MASK}"),
        # value containing '=' is masked completely
        (f"/x?token={FAKE}==&b=2", f"/x?token={MASK}&b=2"),
    ],
)
def test_redact_query_params_masks_sensitive_values(raw, expected):
    assert redact_query_params(raw) == expected


@pytest.mark.parametrize(
    "raw",
    [
        "/health",
        "/transfer/status",
        "/x?foo=bar&baz=1",
        # bare `key` is a non-secret Android resource name on the Java backend
        "/get-resource?mode=get&key=app_name",
        # names that merely contain a sensitive word are not masked
        "/x?token_type=bearer&mytoken=1&tokens=2",
        # empty value / flag without '=': nothing to hide, left as written
        "/x?token=&a=1",
        "/x?token[]=&a=1",
        # matrix params / ';' separators without a sensitive name
        "/a;jsessionid=abc/b?x=1",
        "/a;b",
        "/a%3Bjsessionid%3Dabc/b",
        "/caf%C3%A9/%3B",
        "/x?a=1;b=2",
        "/x?tokenx[]=1&my[token]=2",
        # names that contain a sensitive word but are legitimate elsewhere
        "/x?max_tokens=5&token_type=bearer&secrets_dir=a&key=app_name",
        # 4th encoding layer: not "token" for the server either (documented)
        "/x?%25252574oken=1",
        # over-long names are not decoded and treated as non-sensitive
        "/x?" + "%25" * 200 + "74oken=1",
        "/x?token&a=1",
        "?",
        "",
    ],
)
def test_redact_query_params_leaves_other_input_unchanged(raw):
    assert redact_query_params(raw) is raw


def _access_record(path):
    # Exactly what uvicorn's h11/httptools protocols log for each request.
    return logging.LogRecord(
        "uvicorn.access", logging.INFO, __file__, 0,
        '%s - "%s %s HTTP/%s" %d', ("127.0.0.1:5555", "GET", path, "1.1", 400), None,
    )


def test_filter_rewrites_uvicorn_access_record_and_formatter_still_works():
    record = _access_record(f"/transfer/status?token={FAKE}&foo=bar")
    assert QueryParamRedactionFilter().filter(record) is True
    assert record.args == ("127.0.0.1:5555", "GET", f"/transfer/status?token={MASK}&foo=bar", "1.1", 400)

    line = AccessFormatter('%(client_addr)s - "%(request_line)s" %(status_code)s', use_colors=False).format(record)
    assert line == f'127.0.0.1:5555 - "GET /transfer/status?token={MASK}&foo=bar HTTP/1.1" 400 Bad Request'
    assert FAKE not in line


def test_filter_leaves_records_without_urls_alone():
    record = _access_record("/health?foo=bar")
    args = record.args
    QueryParamRedactionFilter().filter(record)
    assert record.args is args  # untouched, not even copied

    record = logging.LogRecord("x", logging.INFO, __file__, 0, "count=%d", (3,), None)
    QueryParamRedactionFilter().filter(record)
    assert record.getMessage() == "count=3"


def test_filter_redacts_preformatted_message_but_not_format_strings():
    record = logging.LogRecord("x", logging.WARNING, __file__, 0, f"fetching /a?token={FAKE}", None, None)
    QueryParamRedactionFilter().filter(record)
    assert record.getMessage() == f"fetching /a?token={MASK}"

    # With args, msg is a format string: it is left alone, the args are redacted.
    record = logging.LogRecord("x", logging.WARNING, __file__, 0, "url=%s n=%d", (f"/a?token={FAKE}", 1), None)
    QueryParamRedactionFilter().filter(record)
    assert record.getMessage() == f"url=/a?token={MASK} n=1"


def test_filter_redacts_httpx_url_objects():
    record = logging.LogRecord(
        "httpx", logging.INFO, __file__, 0, 'HTTP Request: %s %s "%s %d %s"',
        ("GET", httpx.URL(f"http://127.0.0.1:8650/transfer/status?token={FAKE}&x=1"), "HTTP/1.1", 200, "OK"),
        None,
    )
    QueryParamRedactionFilter().filter(record)
    assert record.getMessage() == (
        f'HTTP Request: GET http://127.0.0.1:8650/transfer/status?token={MASK}&x=1 "HTTP/1.1 200 OK"'
    )


def test_uvicorn_log_config_keeps_uvicorn_defaults_and_adds_filter():
    from uvicorn.config import LOGGING_CONFIG

    config = uvicorn_log_config()
    assert config["filters"]["delamain_redact_query"]["()"] is QueryParamRedactionFilter
    # exactly one attachment per record path (H1): the access logger, the default handler
    assert "filters" not in config["handlers"]["access"]
    assert config["handlers"]["default"]["filters"] == ["delamain_redact_query"]
    assert config["loggers"]["uvicorn.access"]["filters"] == ["delamain_redact_query"]
    # same formatters/streams/levels as uvicorn's default; the default is not mutated
    assert config["formatters"] == LOGGING_CONFIG["formatters"]
    assert config["handlers"]["access"]["stream"] == LOGGING_CONFIG["handlers"]["access"]["stream"]
    assert config["loggers"]["uvicorn.access"]["level"] == LOGGING_CONFIG["loggers"]["uvicorn.access"]["level"]
    assert "filters" not in LOGGING_CONFIG["handlers"]["access"]

    # and dictConfig/uvicorn accept it (exercised for real in the e2e test)
    import uvicorn

    uvicorn.Config(app=lambda *a: None, log_config=config)  # __init__ applies dictConfig
    try:
        access = logging.getLogger("uvicorn.access")
        assert sum(isinstance(f, QueryParamRedactionFilter) for f in access.filters) == 1
    finally:
        logging.getLogger("uvicorn.access").filters.clear()


@pytest.fixture
def isolated_root_handler():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(logging.Formatter("%(name)s: %(message)s"))
    root = logging.getLogger()
    httpx_logger = logging.getLogger("httpx")
    old_level = httpx_logger.level
    root.addHandler(handler)
    httpx_logger.setLevel(logging.INFO)
    try:
        yield stream, handler
    finally:
        root.removeHandler(handler)
        httpx_logger.setLevel(old_level)
        for h in root.handlers:
            h.filters[:] = [f for f in h.filters if not isinstance(f, QueryParamRedactionFilter)]
        access = logging.getLogger("uvicorn.access")
        access.filters[:] = [f for f in access.filters if not isinstance(f, QueryParamRedactionFilter)]


class _OkHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"{}"
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):  # keep the fake backend quiet
        pass


def test_install_log_redaction_masks_real_httpx_request_log(isolated_root_handler):
    """The gateway forwards requests to the Java backend with httpx, which logs every
    request URL at INFO through the root handlers that main.py's basicConfig sets up."""
    stream, handler = isolated_root_handler
    install_log_redaction()
    install_log_redaction()  # idempotent
    assert sum(isinstance(f, QueryParamRedactionFilter) for f in handler.filters) == 1
    # uvicorn.access does not propagate to root; it gets its filter from uvicorn_log_config()
    access = logging.getLogger("uvicorn.access")
    assert not any(isinstance(f, QueryParamRedactionFilter) for f in access.filters)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _OkHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        port = server.server_address[1]
        with httpx.Client() as client:
            resp = client.get(
                f"http://127.0.0.1:{port}/transfer/status", params={"token": FAKE, "x": "1"}
            )
        assert resp.status_code == 200
    finally:
        server.shutdown()
        server.server_close()

    out = stream.getvalue()
    assert f"GET http://127.0.0.1:{port}/transfer/status?token={MASK}&x=1" in out
    assert FAKE not in out


def test_main_passes_redacting_log_config_to_uvicorn(monkeypatch):
    """main.main() is the only launch path (docker/entrypoint.sh, the
    delamain-gateway console script, `python main.py`); it must hand uvicorn the
    redacting log config and keep FastMCP's default log level."""
    import fastmcp

    captured = {}

    def fake_run(self, transport=None, show_banner=None, **kwargs):
        captured["transport"] = transport
        captured.update(kwargs)

    monkeypatch.setattr(fastmcp.FastMCP, "run", fake_run)
    monkeypatch.setenv("DELAMAIN_AUTH_TOKENS", "fake-mcp-token-for-tests")
    monkeypatch.setattr(sys, "argv", ["main.py", "--host", "127.0.0.1", "--port", "18651"])
    try:
        main.main()
    finally:
        from src.registry.instance_registry import InstanceRegistry

        InstanceRegistry.clear_all()
        for h in logging.getLogger().handlers:
            h.filters[:] = [f for f in h.filters if not isinstance(f, QueryParamRedactionFilter)]
        access = logging.getLogger("uvicorn.access")
        access.filters[:] = [f for f in access.filters if not isinstance(f, QueryParamRedactionFilter)]

    assert captured["transport"] == "http"
    uv = captured["uvicorn_config"]
    assert uv["log_level"] == fastmcp.settings.log_level.lower()
    assert uv["log_config"]["loggers"]["uvicorn.access"]["filters"] == ["delamain_redact_query"]


# ---------------------------------------------------------------------------
# H1 (Elliot, PR #20 review): the filter runs synchronously on the single event
# loop for every access-log line and the request target is unauthenticated input,
# so redaction must stay linear. 100K-char inputs (h11 caps a request line at 16 KB)
# must each take well under a second; the quadratic array-suffix regex took ~4 s.
# ---------------------------------------------------------------------------

_BIG = 100_000


_LINEAR_CASES = [
        ("query name of '['", "/x?" + "[" * _BIG + "=1"),
        ("query name of '[' then ']'", "/x?" + "[" * _BIG + "]=1"),
        ("query of ';'", "/x?" + ";" * _BIG + "=1"),
        ("query of '&'", "/x?" + "&" * _BIG + "token=1"),
        ("query of '%25'", "/x?" + "%25" * (_BIG // 3) + "=1"),
        ("many short params", "/x?" + "a=1&" * (_BIG // 4) + f"token={FAKE}"),
        ("many '[' params", "/x?" + "[=1&" * (_BIG // 4)),
        ("path of '['", "/" + "[" * _BIG),
        ("matrix name of '['", "/a;" + "[" * _BIG + "=1"),
        ("matrix of ';'", "/a" + ";" * _BIG),
        ("matrix of '%3B'", "/a" + "%3B" * (_BIG // 3) + "token%3D1"),
        ("matrix of '%3'", "/a;" + "%3" * (_BIG // 2) + "=1"),
        ("matrix values", "/a" + ";t=1" * (_BIG // 4)),
        ("long value", f"/x?token={'A' * _BIG}"),
]


@pytest.mark.parametrize("label, text", _LINEAR_CASES, ids=[c[0] for c in _LINEAR_CASES])
def test_redaction_is_linear_on_long_adversarial_input(label, text):
    best = min(_timed(redact_query_params, text) for _ in range(3))
    assert best < 0.5, f"{label}: {best:.3f}s"
    # and through the filter, as uvicorn's access logger calls it
    record = _access_record(text)
    assert _timed(QueryParamRedactionFilter().filter, record) < 0.5


def _timed(fn, arg):
    t0 = time.perf_counter()
    fn(arg)
    return time.perf_counter() - t0


def test_array_suffix_still_detected_after_h1_fix():
    for name in ("token[]", "token[0]", "token%5B%5D", "api_key[]", "TOKEN[x]"):
        assert redact_query_params(f"/x?{name}={FAKE}") == f"/x?{name}={MASK}"


def test_filter_runs_once_per_access_record_with_main_wiring(monkeypatch):
    """main.main() wiring = install_log_redaction() + uvicorn's dictConfig of
    uvicorn_log_config(): an access record must pass the filter exactly once
    (it ran 3 times before the H1 fix)."""
    import uvicorn

    calls = []
    original = QueryParamRedactionFilter.filter

    def counting(self, record):
        calls.append(record)
        return original(self, record)

    monkeypatch.setattr(QueryParamRedactionFilter, "filter", counting)
    access = logging.getLogger("uvicorn.access")
    install_log_redaction()
    uvicorn.Config(app=lambda *a: None, log_config=uvicorn_log_config(), log_level="info")
    sink = io.StringIO()
    extra = logging.StreamHandler(sink)
    access.addHandler(extra)
    try:
        access.info('%s - "%s %s HTTP/%s" %d', "127.0.0.1:1", "GET", f"/x?token={FAKE}", "1.1", 200)
        assert len(calls) == 1
        assert FAKE not in sink.getvalue()
    finally:
        access.removeHandler(extra)
        access.filters[:] = [f for f in access.filters if not isinstance(f, QueryParamRedactionFilter)]
        for h in logging.getLogger().handlers:
            h.filters[:] = [f for f in h.filters if not isinstance(f, QueryParamRedactionFilter)]
