"""P1 memory: the jadx JVM heap must be derived from the CONTAINER's memory limit,
not hardcoded and not left to the JVM's default.

Production incident (2026-07-22, 10.0.5.31): the container ran without `docker run -m`,
so `-XX:MaxRAMPercentage=60` was computed against the HOST's 82 GB → a 49 GB heap ceiling.
G1 committed ~48 GB and never returned it to the OS, on a shared box also running other
workloads. Design decision (Yuki 74b15d98): detect the container limit at startup, subtract
a gateway+OS reserve, and cap the heap at a level jadx actually benefits from.

These tests pin the arithmetic and the JAVA_OPTS override contract; they are pure functions
over injected paths/values, so no container or JVM is needed.
"""

import pytest

from src import heap_config


# --- detection ------------------------------------------------------------------

def test_detects_cgroup_v2_limit(tmp_path):
    v2 = tmp_path / "memory.max"
    v2.write_text("4294967296\n")  # 4 GiB
    limit, source = heap_config.detect_memory_limit_bytes(
        cgroup_v2=str(v2), cgroup_v1=str(tmp_path / "absent"), meminfo=str(tmp_path / "absent2"))
    assert limit == 4 * 1024**3
    assert source == "cgroup-v2"


def test_unlimited_cgroup_v2_falls_back_to_cgroup_v1(tmp_path):
    v2 = tmp_path / "memory.max"
    v2.write_text("max\n")  # cgroup v2 spelling for "no limit"
    v1 = tmp_path / "memory.limit_in_bytes"
    v1.write_text(str(2 * 1024**3))
    limit, source = heap_config.detect_memory_limit_bytes(
        cgroup_v2=str(v2), cgroup_v1=str(v1), meminfo=str(tmp_path / "absent"))
    assert limit == 2 * 1024**3
    assert source == "cgroup-v1"


def test_cgroup_v1_sentinel_is_treated_as_unlimited_and_falls_back_to_meminfo(tmp_path):
    """cgroup v1 spells 'unlimited' as a near-int64 sentinel, not a real limit."""
    v1 = tmp_path / "memory.limit_in_bytes"
    v1.write_text("9223372036854771712\n")
    meminfo = tmp_path / "meminfo"
    meminfo.write_text("MemTotal:       85899345 kB\nMemFree: 123 kB\n")
    limit, source = heap_config.detect_memory_limit_bytes(
        cgroup_v2=str(tmp_path / "absent"), cgroup_v1=str(v1), meminfo=str(meminfo))
    assert limit == 85899345 * 1024
    assert source == "meminfo"


def test_no_source_available_returns_none(tmp_path):
    limit, source = heap_config.detect_memory_limit_bytes(
        cgroup_v2=str(tmp_path / "a"), cgroup_v1=str(tmp_path / "b"), meminfo=str(tmp_path / "c"))
    assert limit is None
    assert source == "unknown"


# --- heap arithmetic ------------------------------------------------------------

def test_heap_scales_with_a_small_container():
    # 4 GiB container: 60% = 2457 MB, and that still leaves > the 1024 MB reserve.
    assert heap_config.compute_heap_mb(4 * 1024**3) == 2457


def test_heap_is_capped_so_a_huge_host_does_not_produce_a_huge_heap():
    """The production bug: 82 GB visible → must NOT yield a ~49 GB heap."""
    heap = heap_config.compute_heap_mb(82 * 1024**3)
    assert heap == heap_config.DEFAULT_MAX_MB
    assert heap < 12 * 1024


def test_reserve_wins_over_percentage_on_a_mid_size_container():
    # 2 GiB container: 60% = 1228 MB, but limit - reserve = 1024 MB is tighter → reserve wins.
    assert heap_config.compute_heap_mb(2 * 1024**3) == 1024


def test_tiny_container_falls_back_to_the_floor_rather_than_a_nonsensical_heap():
    # 768 MB container: limit - reserve is negative; never emit <= 0 or a silly heap.
    assert heap_config.compute_heap_mb(768 * 1024**2) == heap_config.DEFAULT_MIN_MB


def test_unknown_limit_yields_no_heap_flag():
    """Detection failed → do not guess; leave the JVM default rather than mis-size."""
    assert heap_config.compute_heap_mb(None) is None


# --- JAVA_OPTS contract ---------------------------------------------------------

def test_emits_xmx_and_periodic_gc_by_default():
    opts = heap_config.build_java_opts("", 2457)
    assert "-Xmx2457m" in opts
    assert "-XX:G1PeriodicGCInterval=300000" in opts


def test_operator_xmx_override_is_respected():
    opts = heap_config.build_java_opts("-Xmx3g", 2457)
    assert "-Xmx2457m" not in opts
    assert opts.startswith("-Xmx3g")


def test_operator_max_ram_percentage_override_is_respected():
    """An explicit MaxRAMPercentage is an operator decision about the heap ceiling too."""
    opts = heap_config.build_java_opts("-XX:MaxRAMPercentage=75", 2457)
    assert "-Xmx" not in opts


def test_existing_periodic_gc_is_not_duplicated():
    opts = heap_config.build_java_opts("-XX:G1PeriodicGCInterval=60000", 2457)
    assert opts.count("G1PeriodicGCInterval") == 1


def test_existing_opts_are_preserved():
    opts = heap_config.build_java_opts("-Dfoo=bar", 2457)
    assert "-Dfoo=bar" in opts
    assert "-Xmx2457m" in opts


# --- GC thread cap ---------------------------------------------------------------
# Cold-warmup incident (2026-07-23, 10.0.5.31): the container ran without `docker run
# --cpus`, so the JVM saw all 48 host cores and G1 spun up ~46 GC threads (33 Parallel +
# 8 Conc + 5 Refine). With the cold-warmup live set filling the heap, those threads burned
# ~30 cores in continuous GC while only 6 decompile workers made progress → throughput fell
# from ~216 to ~5.8 classes/s. Fix: pin the GC thread count directly. NOT via
# -XX:ActiveProcessorCount, which would ALSO shrink Runtime.availableProcessors() and thereby
# starve WarmupManager's decompile workers (byCores=max(2,cores/4)) and jadx's decoder threads.

def test_emits_gc_thread_caps_by_default():
    opts = heap_config.build_java_opts("", 2457)
    assert "-XX:ParallelGCThreads=8" in opts
    assert "-XX:ConcGCThreads=2" in opts


def test_never_emits_active_processor_count():
    """ActiveProcessorCount would cascade into WarmupManager worker sizing — never use it."""
    opts = heap_config.build_java_opts("", 2457)
    assert "ActiveProcessorCount" not in opts


def test_operator_parallel_gc_threads_override_is_respected():
    opts = heap_config.build_java_opts("-XX:ParallelGCThreads=24", 2457)
    assert opts.count("ParallelGCThreads") == 1
    assert "-XX:ParallelGCThreads=24" in opts


def test_gc_thread_cap_disabled_when_zero(monkeypatch):
    monkeypatch.setattr(heap_config, "DEFAULT_GC_THREADS", 0)
    opts = heap_config.build_java_opts("", 2457)
    assert "ParallelGCThreads" not in opts
    assert "ConcGCThreads" not in opts


def test_gc_thread_cap_is_env_tunable(monkeypatch):
    monkeypatch.setattr(heap_config, "DEFAULT_GC_THREADS", 12)
    opts = heap_config.build_java_opts("", 2457)
    assert "-XX:ParallelGCThreads=12" in opts
    assert "-XX:ConcGCThreads=3" in opts
