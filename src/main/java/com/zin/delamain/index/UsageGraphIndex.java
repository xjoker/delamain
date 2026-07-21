package com.zin.delamain.index;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Precomputed, immutable class-level usage (reverse-reference) graph.
 *
 * <p><b>Why this exists.</b> An APK is immutable once loaded, so "who references class X"
 * never changes. The legacy path computed it per query via {@code JavaClass.getUseIn()} plus
 * decompiling every referencing class to resolve precise lines — O(callers) work that took
 * &gt;60s for heavily-referenced classes. A warmup probe proved that after Phase-1 decompile,
 * raw {@code getUseIn()} costs ~2µs/class, so the entire reverse graph (~2.1M edges for a
 * 237k-class APK) can be harvested in ~0.5s and persisted in ~25MB. Runtime xref then becomes
 * a pure array lookup.</p>
 *
 * <h2>ID model</h2>
 * This index owns its own deterministic {@code class → int id} mapping derived from a
 * caller-supplied list sorted by full name (id = index in that list). The same sort order is
 * used at build time and at persistent-restore time, so stored edge ids always map back to the
 * same classes for a given APK (keyed by input hash in {@link UsageGraphStore}).
 *
 * <h2>Thread safety</h2>
 * Built once during warmup Phase 3 (or restored from disk), then read-only. {@code volatile}
 * publication makes the fully-built arrays visible to reader threads. Lookups are lock-free.
 */
public final class UsageGraphIndex {

    private static final Logger logger = LoggerFactory.getLogger(UsageGraphIndex.class);

    // classToReferrers[targetId] = source-class ids that reference the target ("who calls X").
    private static volatile int[][] classToReferrers = null;
    // classToCallees[srcId] = target-class ids that src references ("what X calls") — the
    // transpose of classToReferrers, derived in one pass at build/restore (no extra harvest).
    private static volatile int[][] classToCallees = null;
    private static volatile JavaClass[] idToClass = null;
    private static volatile IdentityHashMap<JavaClass, Integer> classToId = null;
    private static volatile boolean ready = false;
    private static volatile long edgeCount = 0;

    private UsageGraphIndex() {}

    public static boolean isReady() {
        return ready;
    }

    public static int classCount() {
        JavaClass[] arr = idToClass;
        return arr == null ? 0 : arr.length;
    }

    public static long edgeCount() {
        return edgeCount;
    }

    /**
     * Builds the deterministic id mapping from the given classes (must be sorted by full name)
     * and returns it, WITHOUT harvesting edges. Used on the persistent-restore path so that
     * loaded edge ids resolve against the current session's classes.
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
     * Harvests the full class-level reverse graph from {@code getUseIn()}.
     * Must be called after Phase-1 decompile so usage info is built and cheap to read.
     * Call {@link #assignIds} first (build() does it internally if not yet assigned).
     *
     * @param sortedClasses classes sorted by full name (stable id order)
     */
    public static void build(List<JavaClass> sortedClasses) {
        long t0 = System.nanoTime();
        assignIds(sortedClasses);
        IdentityHashMap<JavaClass, Integer> c2id = classToId;
        int n = sortedClasses.size();
        int[][] adj = new int[n][];
        long edges = 0;

        // Reusable buffer for distinct source ids per target.
        for (int t = 0; t < n; t++) {
            JavaClass target = sortedClasses.get(t);
            List<JavaNode> uses;
            try {
                uses = target.getUseIn();
            } catch (Exception e) {
                adj[t] = EMPTY;
                continue;
            }
            if (uses == null || uses.isEmpty()) {
                adj[t] = EMPTY;
                continue;
            }
            // Collect distinct declaring-class ids of referencing nodes.
            int[] tmp = new int[uses.size()];
            int k = 0;
            for (JavaNode u : uses) {
                JavaClass src = declaringClassOf(u);
                if (src == null) continue;
                Integer sid = c2id.get(src);
                if (sid == null) continue;       // referrer outside the indexed set
                if (sid == t) continue;          // ignore self-reference
                tmp[k++] = sid;
            }
            adj[t] = dedupSorted(tmp, k);
            edges += adj[t].length;
        }

        classToReferrers = adj;
        classToCallees = transpose(adj, n);
        edgeCount = edges;
        ready = true;
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        logger.info("[JAI] UsageGraphIndex built: {} classes, {} edges (+ transpose) in {} ms", n, edges, ms);
    }

    /** Returns the classes that reference {@code target}, or null if the index is not ready. */
    public static List<JavaClass> referrersOf(JavaClass target) {
        if (!ready) return null;
        IdentityHashMap<JavaClass, Integer> c2id = classToId;
        int[][] adj = classToReferrers;
        JavaClass[] id2c = idToClass;
        if (c2id == null || adj == null || id2c == null) return null;
        Integer tid = c2id.get(target);
        if (tid == null) return null;
        int[] refs = adj[tid];
        List<JavaClass> out = new ArrayList<>(refs.length);
        for (int sid : refs) {
            if (sid >= 0 && sid < id2c.length && id2c[sid] != null) out.add(id2c[sid]);
        }
        return out;
    }

    /**
     * Multi-hop reverse-reachability ("who can reach this class") via BFS over the precomputed
     * graph — instant, no decompile. Answers the core RE question "what call paths lead to X".
     *
     * @param target    the class to trace callers of
     * @param maxDepth  hop limit (1 = direct callers)
     * @param maxNodes  safety cap on total discovered classes
     * @return layered result with per-depth class lists, or null if index not ready / unknown class
     */
    public static Map<String, Object> callersChain(JavaClass target, int maxDepth, int maxNodes) {
        return chain(target, classToReferrers, maxDepth, maxNodes, "callers");
    }

    /**
     * Forward multi-hop reachability ("what this class transitively calls/references") via BFS
     * over the transposed graph — the data-flow-direction counterpart of {@link #callersChain}.
     */
    public static Map<String, Object> calleesChain(JavaClass target, int maxDepth, int maxNodes) {
        return chain(target, classToCallees, maxDepth, maxNodes, "callees");
    }

    private static Map<String, Object> chain(JavaClass target, int[][] adj, int maxDepth, int maxNodes, String direction) {
        if (!ready) return null;
        IdentityHashMap<JavaClass, Integer> c2id = classToId;
        JavaClass[] id2c = idToClass;
        if (c2id == null || adj == null || id2c == null) return null;
        Integer start = c2id.get(target);
        if (start == null) return null;

        java.util.BitSet visited = new java.util.BitSet(id2c.length);
        visited.set(start);
        List<Map<String, Object>> layers = new ArrayList<>();
        int[] frontier = {start};
        int discovered = 0;
        boolean truncated = false;

        for (int depth = 1; depth <= maxDepth && frontier.length > 0; depth++) {
            // Collect distinct unvisited referrers of the whole frontier.
            java.util.BitSet nextBits = new java.util.BitSet(id2c.length);
            for (int f : frontier) {
                int[] refs = adj[f];
                if (refs == null) continue;
                for (int s : refs) {
                    if (!visited.get(s)) nextBits.set(s);
                }
            }
            if (nextBits.isEmpty()) break;

            List<String> names = new ArrayList<>();
            int[] nextFrontier = new int[nextBits.cardinality()];
            int fi = 0;
            for (int s = nextBits.nextSetBit(0); s >= 0; s = nextBits.nextSetBit(s + 1)) {
                visited.set(s);
                nextFrontier[fi++] = s;
                discovered++;
                if (discovered <= maxNodes && id2c[s] != null) {
                    names.add(id2c[s].getFullName());
                }
                if (discovered >= maxNodes) { truncated = true; break; }
            }
            Map<String, Object> layer = new java.util.HashMap<>();
            layer.put("depth", depth);
            layer.put("count", names.size());
            layer.put("classes", names);
            layers.add(layer);
            if (truncated) break;
            frontier = (fi == nextFrontier.length) ? nextFrontier : Arrays.copyOf(nextFrontier, fi);
        }

        Map<String, Object> out = new java.util.HashMap<>();
        out.put("target", target.getFullName());
        out.put("direction", direction);
        out.put("max_depth", maxDepth);
        out.put("callers".equals(direction) ? "total_callers" : "total_callees", discovered);
        out.put("truncated", truncated);
        out.put("layers", layers);
        out.put("via", "usage-graph");
        out.put("resolution", "class-level");
        return out;
    }

    /** Returns the classes that {@code target} references ("what X calls"), or null if not ready. */
    public static List<JavaClass> calleesOf(JavaClass target) {
        if (!ready) return null;
        IdentityHashMap<JavaClass, Integer> c2id = classToId;
        int[][] adj = classToCallees;
        JavaClass[] id2c = idToClass;
        if (c2id == null || adj == null || id2c == null) return null;
        Integer tid = c2id.get(target);
        if (tid == null) return null;
        List<JavaClass> out = new ArrayList<>(adj[tid].length);
        for (int sid : adj[tid]) {
            if (sid >= 0 && sid < id2c.length && id2c[sid] != null) out.add(id2c[sid]);
        }
        return out;
    }

    /** Transpose an adjacency array: given target→referrers, produce src→callees. */
    private static int[][] transpose(int[][] adj, int n) {
        int[] outDeg = new int[n];
        for (int[] row : adj) {
            if (row == null) continue;
            for (int v : row) if (v >= 0 && v < n) outDeg[v]++;
        }
        int[][] inv = new int[n][];
        for (int i = 0; i < n; i++) inv[i] = new int[outDeg[i]];
        int[] pos = new int[n];
        for (int s = 0; s < n; s++) {
            int[] row = adj[s];
            if (row == null) continue;
            for (int t : row) if (t >= 0 && t < n) inv[t][pos[t]++] = s;
        }
        return inv;
    }

    // ----- persistence bridge -----

    /** Snapshot the raw adjacency for {@link UsageGraphStore#save}. */
    public static int[][] snapshotAdjacency() {
        return classToReferrers;
    }

    /**
     * Restores edges from a persisted adjacency array. {@link #assignIds} MUST have been called
     * with the same sorted class list first, and {@code adj.length} must equal the class count.
     */
    public static boolean bulkRestore(int[][] adj) {
        JavaClass[] id2c = idToClass;
        if (id2c == null || adj == null || adj.length != id2c.length) {
            logger.warn("[JAI] UsageGraphIndex restore rejected: id mapping not ready or size mismatch "
                + "(adj={}, classes={})", adj == null ? -1 : adj.length, id2c == null ? -1 : id2c.length);
            return false;
        }
        long edges = 0;
        for (int[] row : adj) edges += (row == null ? 0 : row.length);
        classToReferrers = adj;
        classToCallees = transpose(adj, id2c.length);
        edgeCount = edges;
        ready = true;
        logger.info("[JAI] UsageGraphIndex restored: {} classes, {} edges (+ transpose, fast-path)", id2c.length, edges);
        return true;
    }

    public static void clear() {
        ready = false;
        classToReferrers = null;
        classToCallees = null;
        classToId = null;
        idToClass = null;
        edgeCount = 0;
    }

    /** Sort by RAW name — the basis used for stable, deobf-independent class ids. */
    public static List<JavaClass> sortByFullName(List<JavaClass> classes) {
        List<JavaClass> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        return sorted;
    }

    public static Map<String, Object> getStats() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("ready", ready);
        m.put("classes", classCount());
        m.put("edges", edgeCount);
        return m;
    }

    // ----- helpers -----

    private static final int[] EMPTY = new int[0];

    private static JavaClass declaringClassOf(JavaNode node) {
        if (node instanceof JavaClass) return (JavaClass) node;
        if (node instanceof JavaMethod) return ((JavaMethod) node).getDeclaringClass();
        if (node instanceof JavaField) return ((JavaField) node).getDeclaringClass();
        return null;
    }

    /** Sorts the first {@code len} entries of {@code buf} and removes duplicates. */
    private static int[] dedupSorted(int[] buf, int len) {
        if (len == 0) return EMPTY;
        Arrays.sort(buf, 0, len);
        int w = 1;
        for (int i = 1; i < len; i++) {
            if (buf[i] != buf[w - 1]) buf[w++] = buf[i];
        }
        return Arrays.copyOf(buf, w);
    }
}
