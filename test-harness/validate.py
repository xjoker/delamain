#!/usr/bin/env python3
"""
delamain local regression validation script (no third-party deps, urllib only).

Usage:
  # Prerequisite: two local instances already running (see harness/README or run.sh)
  #   28650 = controlled R8-obfuscated sample acme-obf.dex (ground-truth, with mapping.txt)
  #   28651 = real obfuscated APK UnCrackable-Level2.apk
  python3 validate.py

Each assertion prints PASS / FAIL / BUG-PRESENT.
BUG-PRESENT = a live regression marker for a known defect: once fixed, this
assertion should flip to PASS (the script will remind you).
"""
import json, sys, urllib.request, urllib.parse, time, os

CTRL = os.getenv("CTRL_URL", "http://127.0.0.1:28650")   # controlled sample
REAL = os.getenv("REAL_URL", "http://127.0.0.1:28651")   # real APK
TOKEN = os.getenv("JADX_AUTH_TOKEN", "test-token")
HARNESS = os.path.dirname(os.path.abspath(__file__))

npass = nfail = nbug = 0

def call(base, path, method="GET", body=None):
    url = f"{base}{path}"
    data = None
    headers = {"Authorization": f"Bearer {TOKEN}"}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())

def check(name, cond, detail=""):
    global npass, nfail
    tag = "PASS" if cond else "FAIL"
    if cond: npass += 1
    else: nfail += 1
    print(f"  [{tag}] {name}" + (f"  — {detail}" if detail else ""))

def bug(name, present, detail="", fixed_when=""):
    """Known-defect regression marker. present=True means the defect is still there."""
    global nbug, npass
    if present:
        nbug += 1
        print(f"  [BUG-PRESENT] {name}  — {detail}" + (f"  (once fixed, expect: {fixed_when})" if fixed_when else ""))
    else:
        npass += 1
        print(f"  [PASS/FIXED] {name}  — {detail}")

def code_search(base, term):
    """Async code search (the sync path is unreliable within the warmup window)."""
    sub = call(base, f"/submit-code-search?{urllib.parse.urlencode({'search_term': term, 'search_in': 'code'})}", "POST")
    tk = sub.get("ticket", "")
    for _ in range(10):
        r = call(base, f"/code-search-status?ticket={tk}")
        if r.get("search_info", {}).get("timed_out") is not None and r.get("count") is not None:
            return r
        time.sleep(1)
    return r

print("=" * 70)
print("A. Controlled R8 sample (ground-truth, :28650)")
print("=" * 70)
# deobf raw/alias dual track
cls = call(CTRL, "/all-classes")["classes"]
byraw = {c["raw_name"]: c["name"] for c in cls}
check("deobf dual track: raw_name keeps the runtime name", "com.acme.demo.c" in byraw or "com.acme.demo.CreditCard" in byraw)
# Restore the mapping first to get a stable original name (idempotent)
mp = open(os.path.join(HARNESS, "mapping.txt")).read()
am = call(CTRL, "/apply-proguard-mapping", "POST", {"mapping_content": mp})
check("apply-proguard-mapping: 8/8 applied successfully", am.get("applied") == 8 and am.get("failed") == 0, str(am))
cls2 = {c["raw_name"]: c["name"] for c in call(CTRL, "/all-classes")["classes"]}
check("proguard restore: c -> CreditCard", cls2.get("com.acme.demo.c") == "com.acme.demo.CreditCard", cls2.get("com.acme.demo.c"))
check("proguard restore: f -> SecretKeeper$VaultEntry (inner class)", cls2.get("com.acme.demo.f") == "com.acme.demo.SecretKeeper$VaultEntry")

# 🔴 Red line: the Frida overload parameter type must be the raw obfuscated name, never the deobf alias
fh = call(CTRL, "/generate-frida-hook?class_name=com.acme.demo.PaymentProcessor&method_name=processPayment")
script = fh.get("script", "")
check("🔴 red line: Frida overload param uses raw name 'com.acme.demo.c'", ".overload('com.acme.demo.c'" in script)
check("🔴 red line: Frida overload does not contain deobf alias 'C0002c'", "C0002c" not in script)

# C2 code-metadata (available at the Java layer)
cm = call(CTRL, "/code-metadata?class_name=com.acme.demo.PaymentProcessor")
check("C2 code-metadata: available at the Java layer (has_metadata + references)", cm.get("has_metadata") and cm.get("reference_count", 0) > 0, f"refs={cm.get('reference_count')}")

# code search correctness (async stable path)
cs = code_search(CTRL, "ACME_SECRET_TOKEN_9F3A")
check("code search (async): hits class d containing this string", cs.get("count", 0) >= 1, str(cs.get("classes")))

print("=" * 70)
print("B. Real obfuscated APK UnCrackable-Level2 (:28651)")
print("=" * 70)
fi = call(REAL, "/file-info")
total_cls = fi.get("class_count", 0)
check("Real APK loaded (class_count>100 triggers the parallel branch)", total_cls > 100, f"class_count={total_cls}")
# attack-surface implicit-export
asf = call(REAL, "/attack-surface")
acts = asf.get("activities", [])
main = next((a for a in acts if a.get("name", "").endswith("MainActivity")), {})
check("attack-surface: MainActivity implicit_export detection", main.get("implicit_export") is True, main.get("implicit_export_reason", ""))
# native methods
nm = call(REAL, "/search-native-methods?offset=0&count=50")
nlist = nm.get("results", nm.get("methods", nm.get("native_methods", [])))
check("native method detection (JNI entry points, with frida param types)", len(nlist) >= 2, f"count={len(nlist)}")

# HIGH#1 inner class miss —— live regression marker
T = "LifecycleBoundObserver"  # exists in the inner class android.arch.lifecycle.LiveData$...
single = call(REAL, f"/search-classes-by-keyword?search_term={T}&search_in=class").get("search_info", {}).get("total_found", 0)
multi = call(REAL, f"/search-classes-by-keyword?search_term={T}&search_in=class,method,field").get("search_info", {}).get("total_found", 0)
check("control: single-location search finds the inner class", single >= 1, f"single found={single}")
bug("HIGH#1 inner class miss (SearchRoutes parallel branch excludes isInner)",
    present=(single >= 1 and multi == 0),
    detail=f"single-loc found={single} but multi-loc found={multi}",
    fixed_when="multi-loc should also have found>=1")

# H6 real root cause —— the trigram prefilter is not sound for "unindexed classes", producing
# probabilistic false negatives (this is not about the coverage number itself).
# "LoaderInfo" only exists in the code content of the library class android.support.v4.app.LoaderManagerImpl,
# which is not indexed by warmup (the string literal "LoaderInfo{...}" in toString()), and every one of its
# 3-grams happens to also occur in the content of the 5 already-indexed app classes
# (R/CodeCheck/sg.vantagepoint.a.a/a.b/MainActivity), so the trigram prefilter computes a non-empty
# candidate set (trigram_pre_filter_candidates>=1). Before the fix, SearchRoutes.java :603/:623's
# `!contains` check on this candidate set had no isIndexed guard —— the unindexed LoaderManagerImpl was
# never in the candidate set and got silently `continue`d, returning count=0 (even though it genuinely
# exists). After the fix, an `&& CodeContentIndex.isIndexed(cls)` guard was added, so unindexed classes
# always fall through to a real content-scan (which does live decompilation + lazy indexing on its own),
# and this should now be found.
h6 = code_search(REAL, "LoaderInfo")
h6_info = h6.get("search_info", {})
check("H6 real root cause: unindexed library class content is not falsely rejected by the trigram prefilter (LoaderInfo, count>=1)",
      h6.get("count", 0) >= 1,
      f"count={h6.get('count')} prefilter_candidates={h6_info.get('trigram_pre_filter_candidates')} "
      f"classes={h6.get('classes')}")

# H6 honesty signal —— index-stats exposes a total_classes denominator on the same basis as the index,
# plus indexed_pct; the search response exposes index_is_prefilter_only, so an AI caller can tell whether
# "no hit" means genuinely absent or just uncovered by the index.
stats = call(REAL, "/index-stats")["trigram_index"]
idx = stats.get("indexed_classes", 0)
tot_denom = stats.get("total_classes")
cov = (idx / total_cls * 100) if total_cls else 0
check("H6 coverage signal visible: /index-stats exposes total_classes (same basis as the index) + indexed_pct",
      tot_denom is not None and "indexed_pct" in stats,
      f"total_classes={tot_denom} indexed_pct={stats.get('indexed_pct')}")
check("H6 coverage signal visible: code-search response exposes index_is_prefilter_only=true",
      h6_info.get("index_is_prefilter_only") is True,
      f"index_is_prefilter_only={h6_info.get('index_is_prefilter_only')}")
print(f"  [INFO] Absolute coverage (informational only, not a FAIL criterion): trigram indexed={idx}/{total_cls}(incl. library classes) = {cov:.1f}%; "
      f"index's own-basis denominator total_classes={tot_denom} → indexed_pct={stats.get('indexed_pct')}% "
      f"(1.8% is a basis-mismatch false signal; the real root cause is the false-negative behavioral assertion above)")

# Single-target xrefs-to-class coverage gate —— MainActivity is referenced by 3 anonymous inner classes
# via synthetic constructor field references; this kind of reference is only visible at method-level
# use-tracking, so UsageGraphIndex/UsePlacesIndex's class-level index cannot build these edges.
# If the fast path unconditionally trusts the index without a coverage check, these 4 xrefs get
# silently underreported as 0.
MC = "sg.vantagepoint.uncrackable2.MainActivity"

def xref_total(resp):
    return resp.get("pagination", {}).get("total", resp.get("xrefs_count", -1))

xref_snip = call(REAL, f"/xrefs-to-class?class_name={MC}&include_snippet=true")
xref_nosnip = call(REAL, f"/xrefs-to-class?class_name={MC}&include_snippet=false")
batch = call(REAL, f"/batch-xrefs?targets=class:{MC}")
batch_result = (batch.get("results") or [{}])[0]
batch_count = batch_result.get("xrefs_count", -1)

check("single-target xrefs-to-class(include_snippet=true) covers anonymous-inner-class synthetic field refs: count==4",
      xref_total(xref_snip) == 4,
      f"got={xref_total(xref_snip)} via={xref_snip.get('via')}")

# include_snippet=false hits a different index (UsageGraphIndex, not UsePlacesIndex), whose "class-level"
# resolution is a documented dedup-by-referring-class contract (1 row per class), not a count-by-reference-
# location one —— the 3 anonymous inner classes include MainActivity$2 with 2 call sites (<init> +
# onPostExecute), which naturally dedups down to 3 rows rather than 4. What's verified here is that "the
# referrer class set must not be missing anything" (the fix's actual target), not that it must line up
# with batch's by-location count (that's a different-granularity contract).
nosnip_classes = {e.get("raw_class") for e in xref_nosnip.get("references", [])}
batch_classes = {e.get("raw_class") for e in batch_result.get("xrefs", [])}
check("single-target xrefs-to-class(include_snippet=false) referrer class set is complete (none of the 3 anonymous inner classes missing)",
      nosnip_classes == batch_classes and len(nosnip_classes) == 3,
      f"nosnip_classes={nosnip_classes} batch_classes={batch_classes}")

check("cross invariant: single-target(include_snippet=true) count == batch's xrefs count for the same target",
      xref_total(xref_snip) == batch_count,
      f"single(snip)={xref_total(xref_snip)} batch={batch_count}")

print("=" * 70)
print(f"Result: PASS={npass}  FAIL={nfail}  BUG-PRESENT(known-defect live markers)={nbug}")
print("=" * 70)
sys.exit(1 if nfail else 0)
