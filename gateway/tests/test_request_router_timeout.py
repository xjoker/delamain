"""Regression coverage for request-router timeout endpoint classification."""

from src.routing.request_router import (
    TIMEOUT_CODE_READ,
    TIMEOUT_METADATA,
    _infer_timeout,
)


def test_method_signature_uses_metadata_timeout_tier():
    """The Java API endpoint is metadata-only and should use its shorter tier."""
    assert _infer_timeout("method-signature") == TIMEOUT_METADATA


def test_removed_method_signature_alias_uses_default_timeout_tier():
    """The non-existent endpoint must not remain in the metadata allow-list."""
    assert _infer_timeout("get-method-signature") == TIMEOUT_CODE_READ
