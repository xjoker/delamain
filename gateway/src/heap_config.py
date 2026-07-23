"""Container-aware jadx JVM heap sizing.

The jadx backend holds the whole class tree in heap, so its ceiling has to fit the box it
actually runs on. Two wrong answers we explicitly reject:

* **Hardcoded ``-Xmx``** — the same image runs on a 4 GB VPS and an 82 GB server; one number
  cannot be right for both.
* **Bare ``-XX:MaxRAMPercentage``** — the JVM computes it against the *container* limit only
  when one exists. Run without ``docker run -m`` and it silently sizes against the HOST: on
  10.0.5.31 (82 GB, shared) ``MaxRAMPercentage=60`` produced a 49 GB ceiling, G1 committed
  ~48 GB and never gave it back.

So: read the container's own memory limit, subtract a reserve for the Python gateway + OS +
JVM non-heap (metaspace, code cache, thread stacks, and the mmap'd shard index, which wants
page cache and lives OUTSIDE the heap), and cap the result — past ~10 GB extra heap does not
make jadx faster, it just gives G1 more room to hoard RSS.

Run as ``python3 -m src.heap_config`` (from /app/gateway); it prints the flags to prepend to
JAVA_OPTS, or nothing at all when the operator already decided or detection failed.
"""

import os
import sys

# Fraction of the container's memory the jadx heap may claim.
DEFAULT_PERCENT = int(os.environ.get("DELAMAIN_HEAP_PERCENT", "60"))
# Never leave the rest of the container less than this (gateway ~300 MB + JVM non-heap +
# page cache for the mmap'd shard index + OS).
DEFAULT_RESERVE_MB = int(os.environ.get("DELAMAIN_HEAP_RESERVE_MB", "1024"))
# Above this the heap stops buying throughput and starts costing resident memory.
DEFAULT_MAX_MB = int(os.environ.get("DELAMAIN_HEAP_MAX_MB", "10240"))
# Below this jadx cannot load a mid-size APK at all; better to try and OOM loudly than to
# start with a heap that guarantees failure.
DEFAULT_MIN_MB = int(os.environ.get("DELAMAIN_HEAP_MIN_MB", "512"))

# cgroup v1 writes a near-int64 sentinel instead of "unlimited"; anything at/above this is
# "no limit set", not a real 8-exabyte container.
_V1_UNLIMITED_THRESHOLD = 1 << 62

# Idle-time G1 collection so a container that has finished warmup returns memory to the OS
# instead of sitting on peak RSS forever (5 min).
PERIODIC_GC_FLAG = "-XX:G1PeriodicGCInterval=300000"

# Pin G1's GC thread count. Without `docker run --cpus`, the JVM sees every host core and G1
# sizes ~ncpus ParallelGC threads (33 on a 48-core box) + ~1/4 that many ConcGC threads; during
# cold warmup, when the live set fills the heap, they burn most of the cores in continuous GC and
# starve the decompile workers (2026-07-23 incident: throughput fell to ~5.8 classes/s). We fix
# the count instead of using -XX:ActiveProcessorCount, which would ALSO lower
# Runtime.availableProcessors() and thereby shrink WarmupManager's worker count
# (byCores=max(2,cores/4)) and jadx's decoder threads. 8 is G1's own pick on an 8-core box.
DEFAULT_GC_THREADS = int(os.environ.get("DELAMAIN_GC_THREADS", "8"))


def _read_int(path):
    try:
        with open(path) as fh:
            return int(fh.read().strip())
    except (OSError, ValueError):
        return None


def _read_meminfo_total(path):
    try:
        with open(path) as fh:
            for line in fh:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        return None
    return None


def detect_memory_limit_bytes(
    cgroup_v2="/sys/fs/cgroup/memory.max",
    cgroup_v1="/sys/fs/cgroup/memory/memory.limit_in_bytes",
    meminfo="/proc/meminfo",
):
    """Return ``(limit_bytes, source)`` for the memory this process may use.

    Tries cgroup v2, then v1, then the host's MemTotal. ``(None, "unknown")`` when nothing is
    readable — the caller must then leave the JVM default alone rather than guess.
    """
    v2 = _read_int(cgroup_v2)  # "max" (unlimited) fails the int parse → None
    if v2 is not None and v2 > 0:
        return v2, "cgroup-v2"

    v1 = _read_int(cgroup_v1)
    if v1 is not None and 0 < v1 < _V1_UNLIMITED_THRESHOLD:
        return v1, "cgroup-v1"

    total = _read_meminfo_total(meminfo)
    if total is not None and total > 0:
        return total, "meminfo"

    return None, "unknown"


def compute_heap_mb(
    limit_bytes,
    percent=DEFAULT_PERCENT,
    reserve_mb=DEFAULT_RESERVE_MB,
    max_mb=DEFAULT_MAX_MB,
    min_mb=DEFAULT_MIN_MB,
):
    """Heap ceiling in MB for ``limit_bytes`` of available memory, or ``None`` if unknown.

    Whichever of the three bounds binds first wins: the percentage share, what is left after
    the reserve, and the absolute cap. A container too small for all three falls back to the
    floor.
    """
    if not limit_bytes or limit_bytes <= 0:
        return None
    limit_mb = int(limit_bytes // (1024 * 1024))
    heap = min(limit_mb * percent // 100, limit_mb - reserve_mb, max_mb)
    return max(heap, min_mb)


def build_java_opts(existing, heap_mb):
    """Prepend our derived flags to ``existing`` JAVA_OPTS without overriding the operator.

    An explicit ``-Xmx`` or ``-XX:MaxRAMPercentage`` in JAVA_OPTS means the operator already
    decided the ceiling — we add no heap flag then. The idle-GC flag and the GC thread caps are
    each added unless already present (any operator value wins).
    """
    existing = (existing or "").strip()
    flags = []
    if heap_mb and "-Xmx" not in existing and "MaxRAMPercentage" not in existing:
        flags.append("-Xmx{}m".format(heap_mb))
    if "G1PeriodicGCInterval" not in existing:
        flags.append(PERIODIC_GC_FLAG)
    if DEFAULT_GC_THREADS > 0 and "ParallelGCThreads" not in existing:
        flags.append("-XX:ParallelGCThreads={}".format(DEFAULT_GC_THREADS))
    if DEFAULT_GC_THREADS > 0 and "ConcGCThreads" not in existing:
        flags.append("-XX:ConcGCThreads={}".format(max(1, DEFAULT_GC_THREADS // 4)))
    return " ".join(([existing] if existing else []) + flags).strip()


def main():
    """Print the full JAVA_OPTS line for the entrypoint, plus a one-line rationale on stderr."""
    existing = os.environ.get("JAVA_OPTS", "")
    limit, source = detect_memory_limit_bytes()
    heap_mb = compute_heap_mb(limit)
    opts = build_java_opts(existing, heap_mb)

    if heap_mb and "-Xmx" in opts and "-Xmx" not in existing:
        detail = "heap -Xmx{}m from {} limit {} MB".format(
            heap_mb, source, int(limit // (1024 * 1024)))
    elif heap_mb:
        detail = "operator JAVA_OPTS sets the heap ceiling; detected {} limit {} MB".format(
            source, int(limit // (1024 * 1024)))
    else:
        detail = "memory limit undetectable ({}), leaving the JVM default heap".format(source)
    print("delamain: {}".format(detail), file=sys.stderr)
    print(opts)


if __name__ == "__main__":
    main()
