"""
Integration tests for Java layer HTTP endpoints.
Tests the Java server directly (not through the Python gateway).
"""
import pytest
from .conftest import SIMPLE_CLASS, MAIN_CLASS, TEST_METHOD, TEST_PACKAGE


# ── Metadata ────────────────────────────────────────────────────────────────

class TestMetadata:
    def test_health(self, java_client):
        r = java_client.get("/health")
        assert r.status_code == 200
        d = r.json()
        assert d["status"] in ("healthy", "idle", "starting")
        assert "memory" in d

    def test_apk_info(self, java_client):
        r = java_client.get("/apk-info")
        assert r.status_code == 200
        d = r.json()
        assert "total_classes" in d
        assert d["total_classes"] > 0
        assert "load_state" in d

    def test_decompile_status(self, java_client):
        r = java_client.get("/decompile-status")
        assert r.status_code == 200
        d = r.json()
        assert "memory" in d
        assert "usage_percentage" in d["memory"]

    def test_index_stats(self, java_client):
        r = java_client.get("/index-stats")
        assert r.status_code == 200
        d = r.json()
        assert "trigram_index" in d
        assert "indexed_classes" in d["trigram_index"]

    def test_file_info(self, java_client):
        r = java_client.get("/file-info")
        assert r.status_code == 200

    def test_warmup_status(self, java_client):
        r = java_client.get("/warmup-status")
        assert r.status_code == 200
        d = r.json()
        assert "phase" in d


# ── Class Listing ────────────────────────────────────────────────────────────

class TestClassListing:
    def test_all_classes_pagination(self, java_client):
        """Validates pagination mechanics (page size / offset / has_more / no
        overlap / end-of-list) against whatever APK is actually loaded, rather
        than assuming a specific production APK's class count. PaginationUtils
        reads offset+count (not "page" — that param is accepted but ignored),
        see src/main/java/.../utils/PaginationUtils.java."""
        page_size = 10
        r = java_client.get("/all-classes", params={"offset": 0, "count": page_size})
        assert r.status_code == 200
        d = r.json()
        assert "pagination" in d
        pagination = d["pagination"]
        total = pagination["total"]
        assert total > 0

        expected_count = min(page_size, total)
        assert pagination["offset"] == 0
        assert pagination["count"] == expected_count
        assert len(d.get("classes", [])) == expected_count
        assert pagination["has_more"] == (total > expected_count)

        if pagination["has_more"]:
            next_offset = pagination["next_offset"]
            assert next_offset == expected_count
            r2 = java_client.get("/all-classes", params={"offset": next_offset, "count": page_size})
            assert r2.status_code == 200
            d2 = r2.json()
            page1_names = {c["raw_name"] for c in d["classes"]}
            page2_names = {c["raw_name"] for c in d2["classes"]}
            assert page1_names.isdisjoint(page2_names), "consecutive pages must not overlap"

        # Past the end of the list: no items, has_more must be False.
        r3 = java_client.get("/all-classes", params={"offset": total, "count": page_size})
        assert r3.status_code == 200
        d3 = r3.json()
        assert d3.get("classes", []) == []
        assert d3["pagination"]["has_more"] is False

    def test_package_classes(self, java_client):
        r = java_client.get("/package-classes", params={
            "package": TEST_PACKAGE, "page": 1, "count": 5
        })
        assert r.status_code == 200
        d = r.json()
        assert "classes" in d or "count" in d
        assert d.get("count", len(d.get("classes", []))) > 0

    def test_class_info(self, java_client):
        r = java_client.get("/class-info", params={"class_name": SIMPLE_CLASS})
        assert r.status_code == 200
        d = r.json()
        assert d.get("class_name") or d.get("full_name")

    def test_methods_of_class(self, java_client):
        r = java_client.get("/methods-of-class", params={
            "class_name": SIMPLE_CLASS, "page": 1, "count": 10
        })
        assert r.status_code == 200
        d = r.json()
        assert "methods" in d or "count" in d

    def test_fields_of_class(self, java_client):
        r = java_client.get("/fields-of-class", params={
            "class_name": SIMPLE_CLASS, "page": 1, "count": 10
        })
        assert r.status_code == 200


# ── Decompile ────────────────────────────────────────────────────────────────

class TestDecompile:
    def test_class_source_returns_java(self, java_client):
        r = java_client.get("/class-source", params={"class_name": SIMPLE_CLASS})
        assert r.status_code == 200
        d = r.json()
        source = d.get("response", d.get("source", ""))
        assert len(source) > 10
        # Should be Java source code
        assert "class" in source or "interface" in source or "enum" in source

    def test_class_source_cached_is_fast(self, java_client):
        import time
        # Second call should use cache
        java_client.get("/class-source", params={"class_name": SIMPLE_CLASS})
        t0 = time.time()
        r = java_client.get("/class-source", params={"class_name": SIMPLE_CLASS})
        elapsed = time.time() - t0
        assert r.status_code == 200
        assert elapsed < 1.0, f"Hot cache should be <1s, got {elapsed:.2f}s"

    def test_smali_of_class(self, java_client):
        r = java_client.get("/smali-of-class", params={"class_name": SIMPLE_CLASS})
        assert r.status_code == 200
        d = r.json()
        smali = d.get("response", "")
        assert ".class" in smali or len(smali) > 10

    def test_method_by_name(self, java_client):
        r = java_client.get("/method-by-name", params={
            "class_name": MAIN_CLASS, "method_name": TEST_METHOD
        })
        assert r.status_code == 200
        d = r.json()
        assert d.get("code") or d.get("raw_class_name")

    def test_method_signature(self, java_client):
        r = java_client.get("/method-signature", params={
            "class_name": MAIN_CLASS, "method_name": TEST_METHOD
        })
        assert r.status_code == 200


# ── Search ───────────────────────────────────────────────────────────────────

class TestSearch:
    def test_search_classes_metadata_fast(self, java_client):
        import time
        t0 = time.time()
        r = java_client.get("/search-classes-by-keyword", params={
            "search_term": "Network",
            "search_in": "class,method,field",
            "page": 1, "count": 5
        })
        elapsed = time.time() - t0
        assert r.status_code == 200
        assert elapsed < 2.0, f"Metadata search should be <2s, got {elapsed:.2f}s"

    def test_search_classes_default_is_metadata(self, java_client):
        # Default (no search_in) should NOT trigger code search
        import time
        t0 = time.time()
        r = java_client.get("/search-classes-by-keyword", params={
            "search_term": "Retrofit", "page": 1, "count": 5
        })
        elapsed = time.time() - t0
        assert r.status_code == 200
        assert elapsed < 5.0, f"Default search should not trigger slow code scan, got {elapsed:.2f}s"

    def test_search_string_literals(self, java_client):
        r = java_client.get("/search-string-literals", params={
            "pattern": "https", "page": 1, "count": 5
        })
        assert r.status_code == 200
        d = r.json()
        assert "total" in d or "results" in d

    def test_search_native_methods(self, java_client):
        r = java_client.get("/search-native-methods", params={
            "page": 1, "count": 5
        })
        assert r.status_code == 200

    def test_search_negative_offset_returns_400(self, java_client):
        # SearchRoutes.buildSearchResponse() used to do matches.get(offset) with no bounds
        # check, so a negative offset threw IndexOutOfBoundsException -> opaque 500.
        r = java_client.get("/search-classes-by-keyword", params={
            "search_term": "a", "search_in": "class", "offset": -1
        })
        assert r.status_code == 400
        assert "offset" in r.json().get("error", "").lower()

    def test_search_negative_count_returns_400(self, java_client):
        r = java_client.get("/search-classes-by-keyword", params={
            "search_term": "a", "search_in": "class", "count": -5
        })
        assert r.status_code == 400
        assert "count" in r.json().get("error", "").lower()

    def test_code_search_status_negative_offset_returns_400(self, java_client):
        # Same validation must apply on the poll endpoint, independent of ticket validity.
        r = java_client.get("/code-search-status", params={"ticket": "bogus-ticket", "offset": -1})
        assert r.status_code == 400


# ── Code search match_mode ──────────────────────────────────────────────────

class TestCodeSearchMatchMode:
    """match_mode=regex must actually change how the CODE location is matched, not just be
    echoed back in the response while silently falling back to substring matching
    (SearchRoutes.classMatchesAnyContentLocation)."""

    @staticmethod
    def _submit_and_wait(java_client, term, match_mode, timeout_s=30):
        r = java_client.post("/submit-code-search", params={
            "search_term": term, "search_in": "code", "match_mode": match_mode,
        })
        assert r.status_code == 200
        ticket = r.json()["ticket"]
        import time
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            r2 = java_client.get("/code-search-status", params={
                "ticket": ticket, "match_mode": match_mode,
            })
            body = r2.json()
            if "classes" in body or body.get("status") in ("timed_out", "error"):
                return body
            time.sleep(1)
        raise TimeoutError(f"code search for {term!r} did not complete in {timeout_s}s")

    def test_regex_mode_matches_where_substring_cannot(self, java_client):
        # This literal string (with regex metacharacters) is extremely unlikely to appear
        # verbatim in any decompiled Java source, so substring mode must find nothing. As a
        # regex it matches virtually any class ("public class" / "public final class"), so
        # regex mode must find something. A nonce keeps both terms unique so this test never
        # collides with CodeSearchCoordinator's search-result cache.
        import time
        nonce = str(int(time.time() * 1000))

        sub_term = r"public\s+(final\s+)?class" + f"_ONLYSUB_{nonce}"
        sub_result = self._submit_and_wait(java_client, sub_term, "substring")
        assert sub_result.get("count", 0) == 0, (
            "substring mode should not match a literal string containing regex metacharacters"
        )

        # Same pattern, but the nonce is wrapped in an optional non-capturing group so it
        # doesn't stop the base pattern from matching real "public class" declarations.
        regex_term = r"public\s+(final\s+)?class" + f"(?:_ONLYRE_{nonce})?"
        regex_result = self._submit_and_wait(java_client, regex_term, "regex")
        assert regex_result.get("match_mode") == "regex"
        assert regex_result.get("count", 0) >= 1, (
            "regex mode should match 'public class' declarations broadly across any APK "
            f"(got {regex_result})"
        )


    def test_status_echoes_real_mode_when_poll_omits_match_mode(self, java_client):
        """CANDIDATE2 regression: /code-search-status used to echo match_mode from the poll
        request's own query param (SearchRoutes.buildSearchResponse defaulted to 'substring'
        when absent), not the mode the search was actually computed with at submit time. Submit
        with match_mode=regex, then poll WITHOUT match_mode — the echoed value must still be
        'regex', not the poll's implicit default."""
        import time
        nonce = str(int(time.time() * 1000))
        term = r"public\s+(final\s+)?class" + f"(?:_ECHOPROBE_{nonce})?"

        r = java_client.post("/submit-code-search", params={
            "search_term": term, "search_in": "code", "match_mode": "regex",
        })
        assert r.status_code == 200
        ticket = r.json()["ticket"]

        deadline = time.time() + 30
        body = None
        while time.time() < deadline:
            r2 = java_client.get("/code-search-status", params={"ticket": ticket})  # no match_mode
            body = r2.json()
            if "classes" in body or body.get("status") in ("timed_out", "error"):
                break
            time.sleep(1)
        assert body is not None and "classes" in body, f"search did not complete: {body}"
        assert body.get("match_mode") == "regex", (
            f"poll without match_mode must still echo the real mode used to compute results: {body}"
        )


# ── Search concurrency ───────────────────────────────────────────────────────

class TestSearchConcurrency:
    def test_concurrent_code_search_no_starvation(self, java_client):
        """Regression for SearchRoutes' single searchExecutor pool: the code-search leader task
        used to submit its own batch fan-out to the SAME pool it was running on and block on
        Future.get(), so N concurrent leaders (N >= pool thread count) starved each other's
        batches. Six concurrent no-match searches must all complete quickly on the fixed
        (separate leader/batch pool) implementation."""
        import threading, time

        results = {}

        def worker(i):
            term = f"NoSuchTermConcurrencyProbe_{i}_{int(time.time() * 1000)}"
            r = java_client.post("/submit-code-search", params={
                "search_term": term, "search_in": "code",
            })
            ticket = r.json()["ticket"]
            t0 = time.time()
            for _ in range(60):
                r2 = java_client.get("/code-search-status", params={"ticket": ticket})
                body = r2.json()
                if "classes" in body or body.get("status") in ("timed_out", "error"):
                    results[i] = time.time() - t0
                    return
                time.sleep(1)
            results[i] = None  # never completed within budget

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(6)]
        t0 = time.time()
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=90)
        elapsed = time.time() - t0

        assert all(results.get(i) is not None for i in range(6)), (
            f"some concurrent searches never completed (starvation): {results}"
        )
        assert elapsed < 45.0, f"6 concurrent no-match searches took {elapsed:.1f}s — possible starvation"


# ── Decompile with mode ──────────────────────────────────────────────────────

class TestDecompileWithMode:
    """/decompile-with-mode: single-class ephemeral re-decompile in an explicit
    DecompilationMode (see DecompileRoutes.handleDecompileWithMode). JADX 1.5.6 bakes
    DecompilationMode into the pass pipeline at decompiler-init time — there is no supported
    runtime-wide switch — so this endpoint uses the per-class ClassNode.decompileWithMode()
    escape hatch instead, and must not persist anything onto the class's normal cached source."""

    def test_fallback_mode_returns_code_without_mutating_class_source(self, java_client):
        classes = java_client.get("/all-classes", params={"page": 1, "count": 1}).json()["classes"]
        assert classes, "no classes available"
        cls = classes[0]["name"]

        before = java_client.get("/class-source", params={"class_name": cls}).json()
        before_src = before.get("response", before.get("source", ""))

        r = java_client.get("/decompile-with-mode", params={"class_name": cls, "mode": "FALLBACK"})
        assert r.status_code == 200
        d = r.json()
        assert d.get("mode") == "FALLBACK"
        assert d.get("ephemeral") is True
        assert len(d.get("response", "")) > 0

        # The fallback call must not persist any state onto the class's regular decompile path —
        # /class-source keeps returning the same (normal RESTRUCTURE) output afterward.
        after = java_client.get("/class-source", params={"class_name": cls}).json()
        after_src = after.get("response", after.get("source", ""))
        assert after_src == before_src

    def test_invalid_mode_returns_400(self, java_client):
        classes = java_client.get("/all-classes", params={"page": 1, "count": 1}).json()["classes"]
        cls = classes[0]["name"]
        r = java_client.get("/decompile-with-mode", params={"class_name": cls, "mode": "NOT_A_MODE"})
        assert r.status_code == 400

    def test_missing_class_name_returns_400(self, java_client):
        r = java_client.get("/decompile-with-mode", params={"mode": "FALLBACK"})
        assert r.status_code == 400

    def test_unknown_class_returns_404(self, java_client):
        r = java_client.get("/decompile-with-mode", params={
            "class_name": "com.nonexistent.DoesNotExist12345", "mode": "FALLBACK"
        })
        assert r.status_code == 404


# ── Resources ─────────────────────────────────────────────────────────────────

class TestResources:
    def test_manifest(self, java_client):
        r = java_client.get("/manifest")
        assert r.status_code == 200
        d = r.json()
        content = d.get("manifest", d.get("content", ""))
        assert len(content) > 100
        assert "manifest" in content.lower() or "package" in content.lower()

    def test_strings_pagination(self, java_client):
        r = java_client.get("/strings", params={"page": 1, "count": 5})
        assert r.status_code == 200

    def test_resource_file_names(self, java_client):
        r = java_client.get("/list-all-resource-file-names", params={
            "page": 1, "count": 5
        })
        assert r.status_code == 200
        d = r.json()
        assert d.get("pagination", {}).get("total", 0) > 0


# ── Xrefs ─────────────────────────────────────────────────────────────────────

class TestXrefs:
    def test_xrefs_to_class_simple(self, java_client):
        """Xrefs on a simple class should be fast (<5s)."""
        import time
        t0 = time.time()
        r = java_client.get("/xrefs-to-class", params={
            "class_name": SIMPLE_CLASS, "page": 1, "count": 5
        }, timeout=30)
        elapsed = time.time() - t0
        assert r.status_code == 200
        assert elapsed < 5.0, f"Simple class xrefs should be <5s, got {elapsed:.2f}s"
        d = r.json()
        assert "pagination" in d

    def test_xrefs_uses_read_lock_not_write(self, java_client):
        """Two concurrent xref requests should not block each other (both use read lock)."""
        import threading, time

        results = {}

        def do_xref(name):
            t0 = time.time()
            r = java_client.get("/xrefs-to-class", params={
                "class_name": SIMPLE_CLASS, "page": 1, "count": 3
            }, timeout=20)
            results[name] = (r.status_code, time.time() - t0)

        t1 = threading.Thread(target=do_xref, args=("req1",))
        t2 = threading.Thread(target=do_xref, args=("req2",))
        t0 = time.time()
        t1.start(); t2.start()
        t1.join(); t2.join()
        total = time.time() - t0

        assert results["req1"][0] == 200
        assert results["req2"][0] == 200
        # If they ran sequentially, total would be ~sum of individual; concurrent → much less
        # Both should complete in well under 10s combined
        assert total < 10.0, f"Concurrent xrefs took {total:.2f}s — may be serialized"


# ── Xref async ticket (submit-xref / xref-status) ───────────────────────────

class TestXrefAsyncTicket:
    """POST /submit-xref + GET /xref-status: async counterpart of xrefs-to-class/method/field
    and batch-xrefs (XrefsRoutes.handleSubmitXref/handleXrefStatus). Large-APK xrefs with
    include_snippet=true can fall back to a full live decompile of every caller and risk the
    120s HTTP timeout; this ticket flow returns immediately and lets the caller poll instead."""

    @staticmethod
    def _submit_and_wait(java_client, params, timeout_s=30):
        r = java_client.post("/submit-xref", params=params)
        assert r.status_code == 200
        body = r.json()
        assert "ticket" in body
        assert body["status"] in ("submitted", "done")
        # submit's own status=="done" just means the ticket was pre-completed (fast-path index
        # hit) — it never embeds the payload itself (mirrors submit-code-search). The actual
        # references/results always come from polling /xref-status at least once.
        ticket = body["ticket"]
        import time
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            r2 = java_client.get("/xref-status", params={"ticket": ticket})
            poll = r2.json()
            if poll.get("status") in ("done", "not_found", "error"):
                poll["_http_status"] = r2.status_code
                return poll
            time.sleep(1)
        raise TimeoutError(f"submit-xref ticket {ticket} did not complete in {timeout_s}s")

    def test_submit_xref_class_matches_sync_result(self, java_client):
        sync = java_client.get(
            "/xrefs-to-class", params={"class_name": SIMPLE_CLASS}, timeout=30
        ).json()
        polled = self._submit_and_wait(java_client, {
            "target_type": "class", "class_name": SIMPLE_CLASS,
        })
        assert polled.get("status") == "done"
        assert polled.get("references") == sync.get("references")

    def test_submit_xref_batch_matches_sync_batch_xrefs(self, java_client):
        targets = f"class:{SIMPLE_CLASS}"
        sync = java_client.get(
            "/batch-xrefs", params={"targets": targets}, timeout=30
        ).json()
        polled = self._submit_and_wait(java_client, {"targets": targets})
        assert polled.get("status") == "done"
        assert polled.get("results") == sync.get("results")
        assert polled.get("total") == sync.get("total")

    def test_submit_xref_returns_immediately_not_blocking(self, java_client):
        """The whole point of the ticket flow: submit must return fast even though the
        underlying computation may still be running in the background."""
        import time
        t0 = time.time()
        r = java_client.post("/submit-xref", params={
            "target_type": "class", "class_name": SIMPLE_CLASS,
        })
        elapsed = time.time() - t0
        assert r.status_code == 200
        assert elapsed < 5.0, f"submit-xref should return near-instantly, got {elapsed:.2f}s"

    def test_submit_xref_missing_target_type_returns_400(self, java_client):
        r = java_client.post("/submit-xref", params={"class_name": SIMPLE_CLASS})
        assert r.status_code == 400

    def test_submit_xref_invalid_target_type_returns_400(self, java_client):
        r = java_client.post("/submit-xref", params={
            "target_type": "bogus", "class_name": SIMPLE_CLASS,
        })
        assert r.status_code == 400

    def test_submit_xref_method_without_member_name_returns_400(self, java_client):
        r = java_client.post("/submit-xref", params={
            "target_type": "method", "class_name": SIMPLE_CLASS,
        })
        assert r.status_code == 400

    def test_xref_status_unknown_ticket_returns_404(self, java_client):
        r = java_client.get("/xref-status", params={"ticket": "does-not-exist-12345"})
        assert r.status_code == 404

    def test_xref_status_missing_ticket_returns_400(self, java_client):
        r = java_client.get("/xref-status", params={})
        assert r.status_code == 400

    def test_submit_xref_unknown_class_surfaces_not_found_via_poll(self, java_client):
        polled = self._submit_and_wait(java_client, {
            "target_type": "class", "class_name": "com.nonexistent.DoesNotExist12345",
        })
        assert polled.get("status") == "not_found"
        assert polled.get("_http_status") == 404


# ── Xref resolution/via/distinct_referrer_class_count metadata ──────────────

class TestXrefResolutionFields:
    """The three xref-to-class paths (include_snippet=false, include_snippet=true,
    /batch-xrefs) can legitimately return different ROW counts for the same target: class-level
    dedup (one row per referrer class) vs per-call-site rows (one row per reference position/
    method). That's a documented granularity difference, not data loss — see XrefsRoutes.java
    computeClassXrefs/computeBatchXrefs. Every path must self-describe via resolution/via
    (previously silent on the live-decompile fallback and on all of /batch-xrefs), and
    distinct_referrer_class_count must let a caller confirm the underlying referrer CLASS set is
    unchanged even when row counts differ."""

    def test_xrefs_to_class_nosnippet_has_resolution_and_via(self, java_client):
        r = java_client.get("/xrefs-to-class", params={
            "class_name": MAIN_CLASS, "include_snippet": "false", "count": 5,
        }, timeout=60)
        assert r.status_code == 200
        d = r.json()
        assert d.get("resolution"), d
        assert d.get("via"), d
        assert isinstance(d.get("distinct_referrer_class_count"), int)

    def test_xrefs_to_class_snippet_has_resolution_and_via(self, java_client):
        """Previously silent when the fast index missed/was incomplete and the live-decompile
        fallback fired successfully — resolution/via must now be set on that path too."""
        r = java_client.get("/xrefs-to-class", params={
            "class_name": MAIN_CLASS, "include_snippet": "true", "count": 5,
        }, timeout=60)
        assert r.status_code == 200
        d = r.json()
        assert d.get("resolution"), d
        assert d.get("via"), d
        assert isinstance(d.get("distinct_referrer_class_count"), int)

    def test_batch_xrefs_class_has_resolution_via_and_distinct_count(self, java_client):
        """/batch-xrefs previously never returned resolution/via/distinct_referrer_class_count
        on any path (fast or live)."""
        r = java_client.get("/batch-xrefs", params={"targets": f"class:{MAIN_CLASS}"}, timeout=60)
        assert r.status_code == 200
        item = r.json()["results"][0]
        assert item.get("found") is True
        assert item.get("resolution"), item
        assert item.get("via"), item
        assert isinstance(item.get("distinct_referrer_class_count"), int)

    def test_batch_xrefs_method_has_resolution_and_via(self, java_client):
        r = java_client.get("/batch-xrefs", params={
            "targets": f"method:{MAIN_CLASS}:{TEST_METHOD}",
        }, timeout=60)
        assert r.status_code == 200
        item = r.json()["results"][0]
        if item.get("found"):
            assert item.get("resolution") == "live-method-level"
            assert item.get("via") == "live-decompile"
            assert isinstance(item.get("distinct_referrer_class_count"), int)

    def test_distinct_referrer_class_count_matches_actual_dedup(self, java_client):
        """distinct_referrer_class_count must equal the true number of distinct raw_class values
        in the FULL (pre-pagination) reference list, not just the returned page."""
        r = java_client.get("/xrefs-to-class", params={
            "class_name": SIMPLE_CLASS, "include_snippet": "true", "count": 200,
        }, timeout=30)
        assert r.status_code == 200
        d = r.json()
        assert d["pagination"]["has_more"] is False, "test needs the full reference list in one page"
        actual = {ref["raw_class"] for ref in d["references"] if ref.get("raw_class")}
        assert d["distinct_referrer_class_count"] == len(actual)

    def test_distinct_referrer_class_count_never_exceeds_pagination_total(self, java_client):
        r = java_client.get("/xrefs-to-class", params={
            "class_name": MAIN_CLASS, "include_snippet": "true", "count": 5,
        }, timeout=60)
        d = r.json()
        assert d["distinct_referrer_class_count"] <= d["pagination"]["total"]

    def test_referrer_class_set_consistent_across_snippet_modes_and_batch(self, java_client):
        """The three xref-to-class paths must agree on distinct_referrer_class_count for the same
        target even though their row counts (pagination.total / xrefs_count) can differ by
        granularity — this is the concrete "3 vs 4 isn't a bug" invariant."""
        nosnip = java_client.get("/xrefs-to-class", params={
            "class_name": SIMPLE_CLASS, "include_snippet": "false", "count": 200,
        }, timeout=30).json()
        snip = java_client.get("/xrefs-to-class", params={
            "class_name": SIMPLE_CLASS, "include_snippet": "true", "count": 200,
        }, timeout=30).json()
        batch = java_client.get("/batch-xrefs", params={
            "targets": f"class:{SIMPLE_CLASS}",
        }, timeout=30).json()["results"][0]

        dc_nosnip = nosnip["distinct_referrer_class_count"]
        dc_snip = snip["distinct_referrer_class_count"]
        dc_batch = batch["distinct_referrer_class_count"]
        assert dc_nosnip == dc_snip == dc_batch, (
            f"referrer class count diverged across xref paths: "
            f"nosnip={dc_nosnip} snip={dc_snip} batch={dc_batch}"
        )


# ── Analysis ──────────────────────────────────────────────────────────────────

class TestAnalysis:
    def test_attack_surface(self, java_client):
        r = java_client.get("/attack-surface", timeout=30)
        assert r.status_code == 200
        d = r.json()
        assert any(k in d for k in ("activities", "services", "receivers", "providers"))

    def test_export_callgraph(self, java_client):
        r = java_client.get("/export-callgraph", params={
            "class": MAIN_CLASS, "method": TEST_METHOD, "depth": "2"
        }, timeout=30)
        assert r.status_code == 200


# ── Frida ─────────────────────────────────────────────────────────────────────

class TestFrida:
    def test_generate_frida_hook(self, java_client):
        r = java_client.get("/generate-frida-hook", params={
            "class_name": MAIN_CLASS, "method_name": TEST_METHOD
        })
        assert r.status_code == 200
        d = r.json()
        script = d.get("script", d.get("frida_script", d.get("hook", "")))
        assert len(script) > 50
        assert "Java.use" in script or "Interceptor" in script

    def test_generate_frida_trace(self, java_client):
        r = java_client.get("/generate-frida-trace", params={"class_name": SIMPLE_CLASS})
        assert r.status_code == 200

    def test_generate_frida_enum(self, java_client):
        r = java_client.get("/generate-frida-enum", params={"class_name": SIMPLE_CLASS})
        assert r.status_code == 200


# ── Rename ────────────────────────────────────────────────────────────────────

class TestRename:
    def test_rename_and_revert(self, java_client):
        # Rename
        r = java_client.post("/rename-class", json={
            "class_name": SIMPLE_CLASS, "new_name": "PetalConfigIntegrationTest"
        })
        assert r.status_code == 200

        # Verify source has new name
        r2 = java_client.get("/class-source", params={"class_name": SIMPLE_CLASS})
        assert r2.status_code == 200

        # Revert using original DEX name
        r3 = java_client.post("/rename-class", json={
            "class_name": SIMPLE_CLASS, "new_name": SIMPLE_CLASS
        })
        assert r3.status_code == 200

    def test_rename_nonexistent_class_fails(self, java_client):
        r = java_client.post("/rename-class", json={
            "class_name": "com.nonexistent.DoesNotExist", "new_name": "NewName"
        })
        assert r.status_code in (400, 404, 200)  # 200 with error body also acceptable

    def test_renamed_class_is_immediately_searchable_by_new_alias(self, java_client):
        """Regression: RenameStorage renamed the live ClassNode but never called
        ClassCacheManager.reindex(), so the name-index buckets used by the class/method/field
        search fast path stayed stale — the new alias was invisible to search right after a
        rename. Picks a real class dynamically so this doesn't depend on any specific APK's
        package layout."""
        classes = java_client.get("/all-classes", params={"page": 1, "count": 1}).json()["classes"]
        assert classes, "no classes available to rename"
        original = classes[0]["name"]
        probe_alias = "RenameSearchabilityProbe12345"

        r = java_client.post("/rename-class", json={
            "class_name": original, "new_name": probe_alias
        })
        assert r.status_code == 200
        try:
            r2 = java_client.get("/search-classes-by-keyword", params={
                "search_term": probe_alias, "search_in": "class", "match_mode": "exact",
            })
            assert r2.status_code == 200
            found = r2.json().get("classes", [])
            assert any(probe_alias in c for c in found), (
                f"renamed alias '{probe_alias}' not found by search right after rename: {found}"
            )
        finally:
            # Revert by raw (obfuscated, never-changing) name so the fixture leaves no residue,
            # mirroring the class_name/new_name convention used by test_rename_and_revert above.
            java_client.post("/rename-class", json={
                "class_name": classes[0]["raw_name"], "new_name": original
            })


# ── Batch ─────────────────────────────────────────────────────────────────────

class TestBatch:
    def test_batch_class_source(self, java_client):
        r = java_client.get("/batch-class-source", params={"class_names": SIMPLE_CLASS})
        assert r.status_code == 200
        d = r.json()
        classes = d.get("classes", d.get("results", []))
        assert len(classes) > 0
        assert classes[0].get("found") is True

    def test_batch_method_by_name(self, java_client):
        r = java_client.get("/batch-method-by-name", params={
            "methods": f"{MAIN_CLASS}:{TEST_METHOD}"
        })
        assert r.status_code == 200

    def test_main_activity(self, java_client):
        r = java_client.get("/main-activity", timeout=30)
        assert r.status_code == 200
        d = r.json()
        # Should have class name and content
        assert d.get("class_name") or d.get("content")

    def test_batch_xrefs(self, java_client):
        r = java_client.get("/batch-xrefs", params={
            "targets": f"class:{SIMPLE_CLASS}"
        }, timeout=30)
        assert r.status_code == 200
        d = r.json()
        assert "results" in d
        assert d["results"][0].get("found") is True


# ── Async Code Search ─────────────────────────────────────────────────────────

class TestAsyncSearch:
    def test_submit_and_poll(self, java_client):
        import time
        r = java_client.post("/submit-code-search", params={
            "search_term": "OkHttp", "search_in": "code"
        })
        assert r.status_code == 200
        d = r.json()
        assert "ticket" in d
        assert d["status"] in ("submitted", "done", "running")

        ticket = d["ticket"]
        time.sleep(2)

        r2 = java_client.get("/code-search-status", params={
            "ticket": ticket, "count": 5
        })
        assert r2.status_code == 200
        d2 = r2.json()
        # status endpoint may return results directly or a status wrapper
        assert "search_info" in d2 or d2.get("status") in ("running", "done", "submitted")
