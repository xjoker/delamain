"""Bug #3 regression: create_transfer_token's upload_url must point at the JADX Java
backend (default port 8650), never at the gateway's own address (MCP_SERVER_URL, 8651).

Covers src.tools.transfer_tools.resolve_upload_base priority:
  env (JADX_TRANSFER_PUBLIC_URL) > toml ([server] transfer_public_url) > instance host:port.
"""

import pytest

from src.tools.transfer_tools import resolve_upload_base


def test_env_value_wins_over_everything():
    assert resolve_upload_base(
        "http://env.example:8650", "http://toml.example:8650", "http://10.0.0.5:8650",
    ) == "http://env.example:8650"


def test_toml_value_used_when_env_absent():
    assert resolve_upload_base(
        None, "http://toml.example:8650", "http://10.0.0.5:8650",
    ) == "http://toml.example:8650"


def test_falls_back_to_resolved_instance_address():
    assert resolve_upload_base(None, None, "http://10.0.0.5:8650") == "http://10.0.0.5:8650"


def test_none_when_nothing_configured():
    assert resolve_upload_base(None, None, None) is None


def test_trailing_slash_is_stripped():
    assert resolve_upload_base("http://env.example:8650/", None, None) == "http://env.example:8650"


def test_empty_string_values_are_treated_as_absent():
    # e.g. TOML field left as "" (default) must not shadow a lower-priority source.
    assert resolve_upload_base("", "", "http://10.0.0.5:8650") == "http://10.0.0.5:8650"
