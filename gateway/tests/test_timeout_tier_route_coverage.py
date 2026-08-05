"""Coverage check: gateway timeout-tier endpoint names must be real Java routes.

_METADATA_ENDPOINTS / _HEALTH_ENDPOINTS in request_router.py are a manually
maintained allow-list. A typo or a renamed/removed Java route silently drops
the endpoint to the 120s default tier -- no error, just a behavior change
nobody notices. This test makes that drift a red test instead of a silent
downgrade, by extracting the real registered route names straight from the
Java route-registration source and diffing them against the two gateway
lists.

Source-scan caveat: this is a regex over `app.<verb>("...")` call sites, not
a Javalin route table. It strips comments first (so a commented-out/dead
route can't produce a false pass) and asserts a minimum route count as a
canary that the scan itself still works. If some registration style needs to
be introduced (routes built from a variable, a loop, a shared helper) that
this regex can't see, `test_scanner_finds_a_healthy_number_of_routes` and/or
the coverage tests below will start failing for the wrong reason -- that is
the intended fail-loud behavior; do not loosen the regex to "fix" it without
re-verifying every name it now silently misses.
"""

import re
from pathlib import Path

from src.routing.request_router import _HEALTH_ENDPOINTS, _METADATA_ENDPOINTS

_REPO_ROOT = Path(__file__).resolve().parents[2]
_ROUTES_DIR = _REPO_ROOT / "src/main/java/com/zin/delamain/server/routes"
_SERVER_FILE = _REPO_ROOT / "src/main/java/com/zin/delamain/server/DelamainServer.java"

# Matches app.get("/path", ...), app.post("/path", ...), etc. Deliberately
# narrow (see module docstring): if the Java side starts registering routes
# some other way, this scan must fail loudly rather than quietly miss them.
_ROUTE_CALL_RE = re.compile(r'app\.(?:get|post|put|delete|patch|head|options)\s*\(\s*"([^"]+)"')

# Minimum sane number of distinct routes across the codebase (currently 78).
# A count far below this means the regex stopped matching real registration
# code -- e.g. after a refactor to a builder/DSL style -- and the test must
# fail instead of silently validating against an (almost) empty set.
_MIN_EXPECTED_ROUTE_COUNT = 60


def _strip_java_comments(source: str) -> str:
    """Remove // line comments and /* */ block comments.

    Without this, a commented-out `app.get("/old-route", ...)` would still
    match the regex and produce a false pass for a route that no longer
    exists.
    """
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    source = re.sub(r"//[^\n]*", "", source)
    return source


def _discover_registered_routes() -> set[str]:
    java_files = sorted(_ROUTES_DIR.glob("*.java")) + [_SERVER_FILE]
    assert all(f.is_file() for f in java_files), (
        f"expected Java route source files to exist: {java_files}"
    )

    routes: set[str] = set()
    for java_file in java_files:
        text = _strip_java_comments(java_file.read_text(encoding="utf-8"))
        for match in _ROUTE_CALL_RE.finditer(text):
            routes.add(match.group(1).lstrip("/"))
    return routes


_DISCOVERED_ROUTES = _discover_registered_routes()


def test_scanner_finds_a_healthy_number_of_routes():
    """Canary: if this drops near zero, the regex broke, not the app."""
    assert len(_DISCOVERED_ROUTES) >= _MIN_EXPECTED_ROUTE_COUNT, (
        f"only found {len(_DISCOVERED_ROUTES)} routes via source scan "
        f"(expected >= {_MIN_EXPECTED_ROUTE_COUNT}); the route-registration "
        "scan regex likely no longer matches the real registration style -- "
        "fix the scanner before trusting the coverage assertions below"
    )


def test_every_metadata_endpoint_is_a_real_java_route():
    missing = sorted(name for name in _METADATA_ENDPOINTS if name not in _DISCOVERED_ROUTES)
    assert not missing, (
        f"_METADATA_ENDPOINTS names not found as registered Java routes: {missing} "
        "-- these silently fall through to the 120s default timeout tier instead "
        "of erroring, so a typo here is invisible without this test"
    )


def test_every_health_endpoint_is_a_real_java_route():
    missing = sorted(name for name in _HEALTH_ENDPOINTS if name not in _DISCOVERED_ROUTES)
    assert not missing, (
        f"_HEALTH_ENDPOINTS names not found as registered Java routes: {missing} "
        "-- these silently fall through to the 120s default timeout tier instead "
        "of erroring, so a typo here is invisible without this test"
    )
