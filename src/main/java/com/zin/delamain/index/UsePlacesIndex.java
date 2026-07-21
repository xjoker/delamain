package com.zin.delamain.index;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Precomputed, immutable per-target <b>precise use-places</b> for class-level xref.
 *
 * <p><b>Why this exists.</b> {@code /xrefs-to-class?include_snippet=true} resolves precise call
 * sites via {@code referrer.getUsePlacesFor(codeInfo, targetClass)} — which needs jadx's processed
 * state (decompiled code + metadata). After a {@code FAST_RESTORE} that state is empty, so precise
 * xref returns nothing until referrers are re-decompiled (the "cold window"). This index harvests
 * those use-places ONCE and persists them via {@link UsePlacesStore}, so a restart serves precise
 * xref instantly without depending on jadx processed state.</p>
 *
 * <h2>Memory-bounded source-driven harvest</h2>
 * The harvest is <b>source-driven</b>: it iterates each referrer class R exactly once, decompiles
 * it, computes {@code R.getUsePlacesFor(ci, T)} for every target T that R references
 * ({@link UsageGraphIndex#calleesOf}), then immediately <b>recycles</b> R (unloads its decompiled
 * state + evicts the jadx code cache). Because each class is decompiled once and released right
 * after, the JVM heap stays bounded — eliminating the unbounded accumulation that throttled the
 * old "decompile everything then harvest" approach. Workers run in parallel (jadx handles per-class
 * concurrency; per-source unload is disjoint across workers, and {@code getUsePlacesFor} reads only
 * the referrer's own code metadata + the target's node identity, so concurrent target recycle is
 * safe).
 *
 * <h2>ID model</h2>
 * Reuses the same deterministic {@code class → int id} mapping as {@link UsageGraphIndex}: ids are
 * indexes into a list sorted by <b>raw</b> name (deobf-independent, stable across restarts and
 * keyed by input hash in {@link UsePlacesStore}).
 *
 * <h2>Storage shape (in-memory + on-disk)</h2>
 * {@code perTarget[targetId]} is a flat int array of <i>triples</i>
 * {@code [refId, decompiledLine, sourceLine, ...]}:
 * <ul>
 *   <li>{@code decompiledLine > 0} — a precise call site; {@code sourceLine <= 0} ⇒ unknown. The
 *       one-line snippet is reconstructed from {@link CodeStore} at query time.</li>
 *   <li>{@code decompiledLine == 0} — a class-level fallback (referrer references the target but no
 *       precise textual position was found); {@code sourceLine} is the referrer's definition line.</li>
 * </ul>
 */
public final class UsePlacesIndex {

    private static final Logger logger = LoggerFactory.getLogger(UsePlacesIndex.class);

    private static volatile int[][] perTarget = null;
    private static volatile JavaClass[] idToClass = null;
    private static volatile IdentityHashMap<JavaClass, Integer> classToId = null;
    private static volatile boolean ready = false;

    // Harvest progress — surfaced via WarmupManager.getStatus() → MCP get_warmup_status.
    private static final AtomicBoolean harvestRunning = new AtomicBoolean(false);
    private static final AtomicInteger harvestProcessed = new AtomicInteger(0);
    private static final AtomicInteger harvestTotal = new AtomicInteger(0);

    private UsePlacesIndex() {}

    public static boolean isReady() {
        return ready;
    }

    public static boolean isHarvesting() {
        return harvestRunning.get();
    }

    public static int harvestProcessed() {
        return harvestProcessed.get();
    }

    public static int harvestTotal() {
        return harvestTotal.get();
    }

    public static int classCount() {
        JavaClass[] arr = idToClass;
        return arr == null ? 0 : arr.length;
    }

    /** A single resolved reference, decoded from the stored triples for a target. */
    public static final class Ref {
        public final JavaClass referrer;
        public final int decompiledLine; // 0 ⇒ class-level fallback (no precise position)
        public final int sourceLine;     // <= 0 ⇒ unknown
        Ref(JavaClass referrer, int decompiledLine, int sourceLine) {
            this.referrer = referrer;
            this.decompiledLine = decompiledLine;
            this.sourceLine = sourceLine;
        }
    }

    /**
     * Builds the deterministic id mapping from the given classes (must be sorted by RAW name),
     * WITHOUT harvesting. Mirrors {@link UsageGraphIndex#assignIds}.
     */
    public static void assignIds(List<JavaClass> sortedClasses) {
        int n = sortedClasses.size();
        JavaClass[] id2c = new JavaClass[n];
        IdentityHashMap<JavaClass, Integer> c2id = new IdentityHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            JavaClass c = sortedClasses.get(i);
            id2c[i] = c;
            c2id.put(c, i);
        }
        idToClass = id2c;
        classToId = c2id;
    }

    /**
     * Source-driven, memory-bounded, parallel harvest of precise use-places. Each referrer is
     * decompiled once, used to resolve use-places for all targets it references, then recycled.
     *
     * @param sortedClasses classes sorted by RAW name (same order as {@link #assignIds})
     * @param newLineStr    the decompiler newline string (from {@code wrapper.getArgs()})
     * @param workers       parallel decompile workers (clamped to [1, classCount])
     * @param cancelled     polled to allow early abort (warmup cancel / interrupt)
     * @param recycler      called after each referrer is processed to free its state (unload +
     *                      code-cache evict); may be null (then nothing is freed — not recommended)
     * @return a snapshot of {@code perTarget} for persistence, or {@code null} on failure / cancel
     */
    /**
     * Text-search based harvest using a disk-backed CodeStore. Does NOT call JADX's decompile
     * engine — each class's decompiled source is read directly from {@code codeStore}, so the JVM
     * heap stays bounded regardless of class count. Position matching uses word-boundary text search
     * for both the target's simple name and full qualified name, which is less precise than jadx's
     * metadata-backed {@code getUsePlacesFor} but avoids the memory explosion that re-decompilation
     * causes (jadx recursively loads class dependencies, adding ~20 GB on a 237k-class APK).
     */
    public static int[][] harvest(List<JavaClass> sortedClasses, String newLineStr, int workers,
                                  BooleanSupplier cancelled, Consumer<JavaClass> recycler,
                                  CodeStore codeStore) {
        return harvestInternal(sortedClasses, newLineStr, workers, cancelled, recycler, codeStore);
    }

    public static int[][] harvest(List<JavaClass> sortedClasses, String newLineStr, int workers,
                                  BooleanSupplier cancelled, Consumer<JavaClass> recycler) {
        return harvestInternal(sortedClasses, newLineStr, workers, cancelled, recycler, null);
    }

    private static int[][] harvestInternal(List<JavaClass> sortedClasses, String newLineStr, int workers,
                                           BooleanSupplier cancelled, Consumer<JavaClass> recycler,
                                           CodeStore codeStore) {
        if (!UsageGraphIndex.isReady()) {
            logger.warn("[JAI] UsePlacesIndex.harvest skipped: usage graph not ready");
            return null;
        }
        assignIds(sortedClasses);
        final IdentityHashMap<JavaClass, Integer> c2id = classToId;
        final int n = sortedClasses.size();
        final String nl = newLineStr;
        harvestTotal.set(n);
        harvestProcessed.set(0);
        harvestRunning.set(true);

        // Shared accumulator with striped locks: low contention (n targets ≫ stripes ≫ workers).
        final IntSeq[] acc = new IntSeq[n];
        final int STRIPES = 4096;
        final Object[] locks = new Object[STRIPES];
        for (int i = 0; i < STRIPES; i++) locks[i] = new Object();

        final java.util.concurrent.atomic.AtomicLong places = new java.util.concurrent.atomic.AtomicLong(0);
        final AtomicInteger errors = new AtomicInteger(0);
        final AtomicInteger pos = new AtomicInteger(0);
        long t0 = System.nanoTime();

        int nWorkers = Math.max(1, Math.min(workers, Math.max(1, n)));
        ExecutorService pool = Executors.newFixedThreadPool(nWorkers, r -> {
            Thread t = new Thread(r, "jadx-useplaces-harvest");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        CountDownLatch latch = new CountDownLatch(nWorkers);

        final boolean useCodeStore = codeStore != null;
        for (int w = 0; w < nWorkers; w++) {
            pool.submit(() -> {
                try {
                    int p;
                    while ((p = pos.getAndIncrement()) < n) {
                        if (cancelled.getAsBoolean()) break;
                        JavaClass src = sortedClasses.get(p);
                        int sid = p; // id == index in the sorted list
                        try {
                            List<JavaClass> callees = UsageGraphIndex.calleesOf(src);
                            if (callees != null && !callees.isEmpty()) {
                                if (useCodeStore) {
                                    // Text-search path: read from disk, no JADX re-decompile.
                                    String code = codeStore.get(src.getRawName());
                                    harvestOneSourceText(src, sid, callees, c2id, nl,
                                        acc, locks, STRIPES, places, errors, code);
                                } else {
                                    harvestOneSource(src, sid, callees, c2id, nl,
                                        acc, locks, STRIPES, places, errors);
                                }
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            // Recycle only when using JADX path (text-search doesn't load JADX state).
                            if (!useCodeStore && recycler != null) {
                                try { recycler.accept(src); } catch (Exception ignored) {}
                            }
                            harvestProcessed.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }

        if (cancelled.getAsBoolean()) {
            harvestRunning.set(false);
            logger.info("[JAI] UsePlacesIndex.harvest cancelled at {}/{}", harvestProcessed.get(), n);
            return null;
        }

        // Materialise the shared accumulator into the dense perTarget array.
        int[][] data = new int[n][];
        for (int t = 0; t < n; t++) {
            IntSeq s = acc[t];
            data[t] = (s == null || s.n == 0) ? EMPTY : s.toArray();
            acc[t] = null; // release as we go
        }
        perTarget = data;
        ready = true;
        harvestRunning.set(false);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        logger.info("[JAI] UsePlacesIndex harvested (source-driven, {} workers): {} sources, {} precise places, "
            + "{} errors in {} ms", nWorkers, n, places.get(), errors.get(), ms);
        return data;
    }

    /** Resolve and accumulate use-places for one referrer {@code src} across all its targets. */
    private static void harvestOneSource(JavaClass src, int sid, List<JavaClass> callees,
                                         IdentityHashMap<JavaClass, Integer> c2id, String nl,
                                         IntSeq[] acc, Object[] locks, int stripes,
                                         java.util.concurrent.atomic.AtomicLong places, AtomicInteger errors) {
        ICodeInfo ci;
        try {
            ci = src.getCodeInfo();
        } catch (Exception e) {
            errors.incrementAndGet();
            return;
        }
        boolean hasMeta = ci != null && ci.hasMetadata();
        String code = hasMeta ? ci.getCodeStr() : null;
        int nlLen = (nl == null || nl.isEmpty()) ? 1 : nl.length();
        // Precompute line-start offsets ONCE per source so each position resolves to a line number
        // via binary search (O(log lines)) instead of jadx's O(pos) linear getLineNumForPos —
        // which, called per-position-per-edge, was O(positions × file size) and froze the harvest
        // on large classes.
        int[] lineStarts = (code != null) ? buildLineStarts(code, nl, nlLen) : null;
        int defLine = -1; // lazily computed referrer-definition source line for fallbacks

        for (JavaClass tgt : callees) {
            Integer tid = c2id.get(tgt);
            if (tid == null) continue;
            boolean any = false;
            if (hasMeta && lineStarts != null) {
                try {
                    List<Integer> positions = src.getUsePlacesFor(ci, (JavaNode) tgt);
                    if (positions != null) {
                        for (int up : positions) {
                            int decompLine = lineNumForPos(lineStarts, up);
                            if (isImportLine(code, lineStarts, decompLine, nlLen)) continue;
                            int srcLine = safeSourceLine(src, decompLine);
                            append(acc, locks, stripes, tid, sid, decompLine, srcLine);
                            any = true;
                            places.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
            if (!any) {
                // Class-level fallback: src references tgt but no precise position resolved.
                if (defLine == -1) defLine = defLineFor(src, lineStarts);
                append(acc, locks, stripes, tid, sid, 0, defLine);
            }
        }
    }

    /**
     * Text-search based use-places resolution. Reads code from CodeStore (disk) — no JADX
     * re-decompile. Searches for each target's deobfuscated simple name and full qualified name
     * using word-boundary matching. Less precise than JADX metadata (may include false positives
     * from comments or string literals), but avoids the ~20 GB heap spike caused by JADX loading
     * class dependencies during re-decompilation.
     */
    private static void harvestOneSourceText(JavaClass src, int sid, List<JavaClass> callees,
                                             IdentityHashMap<JavaClass, Integer> c2id, String nl,
                                             IntSeq[] acc, Object[] locks, int stripes,
                                             java.util.concurrent.atomic.AtomicLong places,
                                             AtomicInteger errors, String code) {
        if (code == null || code.isEmpty()) return; // no code in store → skip
        int nlLen = (nl == null || nl.isEmpty()) ? 1 : nl.length();
        int[] lineStarts = buildLineStarts(code, nl, nlLen);

        for (JavaClass tgt : callees) {
            Integer tid = c2id.get(tgt);
            if (tid == null) continue;
            boolean any = false;
            try {
                // Collect candidate positions via word-boundary text search.
                List<Integer> candidates = new ArrayList<>();
                String fullName = safeFullName(tgt);   // qualified deobf name (e.g. "com.example.Foo")
                String simpleName = safeSimpleName(tgt); // simple deobf name (e.g. "Foo")
                if (fullName != null) findWithWordBoundary(code, fullName, candidates);
                if (simpleName != null && simpleName.length() >= 3
                        && !simpleName.equals(fullName)) {
                    findWithWordBoundary(code, simpleName, candidates);
                }
                for (int charPos : candidates) {
                    int decompLine = lineNumForPos(lineStarts, charPos);
                    if (isImportLine(code, lineStarts, decompLine, nlLen)) continue;
                    append(acc, locks, stripes, tid, sid, decompLine, 0);
                    any = true;
                    places.incrementAndGet();
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            }
            if (!any) {
                append(acc, locks, stripes, tid, sid, 0, 0); // class-level fallback
            }
        }
    }

    private static String safeFullName(JavaClass cls) {
        try { return cls.getFullName(); } catch (Exception e) { return null; }
    }

    private static String safeSimpleName(JavaClass cls) {
        try { return cls.getName(); } catch (Exception e) { return null; }
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static void findWithWordBoundary(String code, String name, List<Integer> out) {
        int len = name.length();
        int start = 0;
        while ((start = code.indexOf(name, start)) >= 0) {
            boolean prefixOk = start == 0 || !isIdentPart(code.charAt(start - 1));
            boolean suffixOk = start + len >= code.length() || !isIdentPart(code.charAt(start + len));
            if (prefixOk && suffixOk) out.add(start);
            start++;
        }
    }

    private static void append(IntSeq[] acc, Object[] locks, int stripes,
                               int tid, int refId, int decompLine, int srcLine) {
        synchronized (locks[tid & (stripes - 1)]) {
            IntSeq s = acc[tid];
            if (s == null) acc[tid] = s = new IntSeq();
            s.add3(refId, decompLine, srcLine);
        }
    }

    /**
     * Restores from a persisted triples array. {@link #assignIds} MUST have been called with the
     * same sorted class list first, and {@code data.length} must equal the class count.
     */
    public static boolean bulkRestore(int[][] data) {
        JavaClass[] id2c = idToClass;
        if (id2c == null || data == null || data.length != id2c.length) {
            logger.warn("[JAI] UsePlacesIndex restore rejected: id mapping not ready or size mismatch "
                + "(data={}, classes={})", data == null ? -1 : data.length, id2c == null ? -1 : id2c.length);
            return false;
        }
        long places = 0;
        for (int[] row : data) places += (row == null ? 0 : row.length / 3);
        perTarget = data;
        ready = true;
        logger.info("[JAI] UsePlacesIndex restored: {} targets, {} precise places (fast-path)", id2c.length, places);
        return true;
    }

    /**
     * Returns the precise references TO {@code target}, decoded from the stored triples, or
     * {@code null} if not ready / target unknown to the index (caller falls through to the live
     * path). An empty list means a known target with no referrers.
     */
    public static List<Ref> referencesTo(JavaClass target) {
        if (!ready) return null;
        IdentityHashMap<JavaClass, Integer> c2id = classToId;
        int[][] data = perTarget;
        JavaClass[] id2c = idToClass;
        if (c2id == null || data == null || id2c == null) return null;
        Integer tid = c2id.get(target);
        if (tid == null) return null;
        int[] row = data[tid];
        if (row == null || row.length == 0) return new ArrayList<>(0);
        List<Ref> out = new ArrayList<>(row.length / 3);
        for (int i = 0; i + 2 < row.length; i += 3) {
            int rid = row[i];
            if (rid < 0 || rid >= id2c.length || id2c[rid] == null) continue;
            out.add(new Ref(id2c[rid], row[i + 1], row[i + 2]));
        }
        return out;
    }

    /** Snapshot the raw triples array for {@link UsePlacesStore#save}. */
    public static int[][] snapshot() {
        return perTarget;
    }

    public static void clear() {
        ready = false;
        perTarget = null;
        classToId = null;
        idToClass = null;
        harvestRunning.set(false);
        harvestProcessed.set(0);
        harvestTotal.set(0);
    }

    // ----- helpers -----

    private static final int[] EMPTY = new int[0];

    /** Minimal growable int sequence (triples) — avoids boxing and per-element object overhead. */
    private static final class IntSeq {
        int[] a = new int[6];
        int n = 0;
        void add3(int x, int y, int z) {
            if (n + 3 > a.length) {
                a = java.util.Arrays.copyOf(a, a.length + (a.length >> 1) + 3);
            }
            a[n++] = x; a[n++] = y; a[n++] = z;
        }
        int[] toArray() {
            return (n == a.length) ? a : java.util.Arrays.copyOf(a, n);
        }
    }

    private static int safeSourceLine(JavaClass cls, int decompiledLine) {
        if (decompiledLine <= 0) return 0;
        try {
            int s = cls.getSourceLine(decompiledLine);
            return s > 0 ? s : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Resolve the referrer's class-definition source line (class-level fallback entry). */
    private static int defLineFor(JavaClass cls, int[] lineStarts) {
        if (lineStarts == null) return 0;
        try {
            int defPos = cls.getDefPos();
            if (defPos <= 0) return 0;
            return safeSourceLine(cls, lineNumForPos(lineStarts, defPos));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Char offsets where each line begins ({@code lineStarts[0] == 0}). Built once per source in
     * two O(n) passes; positions then resolve to line numbers via {@link #lineNumForPos} binary
     * search. {@code nl} is the decompiler newline (never empty in practice; guarded anyway).
     */
    private static int[] buildLineStarts(String code, String nl, int nlLen) {
        if (nl == null || nl.isEmpty()) nl = "\n";
        int count = 1;
        for (int idx = 0; (idx = code.indexOf(nl, idx)) >= 0; idx += nlLen) count++;
        int[] starts = new int[count];
        int li = 1, idx = 0, p;
        while (li < count && (p = code.indexOf(nl, idx)) >= 0) {
            starts[li++] = p + nlLen;
            idx = p + nlLen;
        }
        return starts;
    }

    /** 1-based line number for {@code pos}: largest line whose start offset is {@code <= pos}. */
    private static int lineNumForPos(int[] lineStarts, int pos) {
        if (lineStarts == null || lineStarts.length == 0) return 0;
        int lo = 0, hi = lineStarts.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts[mid] <= pos) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans + 1;
    }

    /** True if the 1-based {@code line} (after leading whitespace) is an {@code import } statement. */
    private static boolean isImportLine(String code, int[] lineStarts, int line, int nlLen) {
        if (line <= 0 || line > lineStarts.length) return false;
        int s = lineStarts[line - 1];
        int e = (line < lineStarts.length) ? lineStarts[line] - nlLen : code.length();
        if (e < s) e = code.length();
        int i = s;
        while (i < e && Character.isWhitespace(code.charAt(i))) i++;
        return code.regionMatches(i, "import ", 0, 7);
    }
}
