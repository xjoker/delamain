# Prebaked index volume distribution (second-scale FAST_RESTORE on low-spec machines)

> Background: the index directory under `--index-dir` (`.graph` usage graph /
> `code/` CodeStore / `.shard.N` mmap shards / `.shardcat` shard catalog /
> `.idx` trigram index) is already a portable set of files keyed by the APK's
> `inputHash` — copy the whole directory to another machine, start it against
> **the same APK**, and it goes straight into `FAST_RESTORE` (second-scale
> load), skipping the Phase-1 full decompile and index build. This document
> only covers "how to produce this index volume, how to distribute it, and
> how to verify on the target machine that it's complete and usable" — the
> `FAST_RESTORE` load logic itself is out of scope here.

## 1. Build the index on a large-heap machine

On a machine with enough memory, run one full warmup against the target APK
(a cold start: full decompile + index build):

```bash
# assumes the service was started with --index-dir /data/jadx-index and the
# target APK already loaded
curl -X POST http://localhost:18650/cache/warmup

# poll until running=false and every background task (trigram / shard /
# use-places harvest) has finished
curl -s http://localhost:18650/cache/warmup-status | jq '.running, .phase, .warming_up'
```

A full cold start on a large-heap machine is typically minutes (depends on
APK size). Once it's done, `--index-dir` holds a full set of files named by
`inputHash`:

```
<inputHash>.graph          # usage graph
<inputHash>.idx            # trigram index
<inputHash>.shardcat       # mmap shard catalog
<inputHash>.shard.0 ...    # mmap shard files
<inputHash>.useplaces      # precise reference locations
<inputHash>.manifest.json  # human-readable volume metadata (see below)
code/<inputHash>/...       # CodeStore (decompiled source, persisted to disk)
```

## 2. Package and ship it to a low-spec machine

Package the whole `--index-dir` directory as-is — don't cherry-pick files,
`FAST_RESTORE` needs the full set to match each other:

```bash
tar -C /data/jadx-index -czf jadx-index-<inputHash>.tar.gz .
scp jadx-index-<inputHash>.tar.gz low-spec-host:/tmp/
```

## 3. Unpack and start on the low-spec machine

On the low-spec machine (constrained heap), unpack into the same
`--index-dir` path, then start the service against **the exact same APK
file**:

```bash
mkdir -p /data/jadx-index
tar -C /data/jadx-index -xzf /tmp/jadx-index-<inputHash>.tar.gz

# --index-dir in the startup args must point at this directory, and the APK
# must be identical to the one the index was built from (FAST_RESTORE matches
# by inputHash, so the file content must be unchanged; the path can differ)
java -jar delamain.jar --index-dir /data/jadx-index --apk /path/to/same.apk ...
```

Once started, the service automatically detects the existing
`.graph`/CodeStore/`.shardcat` files matching the loaded APK's `inputHash`
under `--index-dir` and takes the `FAST_RESTORE` branch — completing in
seconds instead of the multi-minute Phase-1 full decompile and index build.

**Verified on low-spec hardware**: a purely in-memory trigram index gets
skipped under memory pressure on a constrained heap (0% availability, code
search permanently degraded to `skipped:low-heap`); with delamain's mmap
shard index (the `.shardcat`/`.shard.N` files carried in this volume), code
search stays **fully covered** on the same constrained-heap host — shards
are read-only mmap regions, so they consume no JVM heap and aren't bound by
heap size.

## 4. Verifying volume completeness and match (manifest / index-stats)

After starting on the low-spec machine, verify this index volume is
"completely baked" rather than a half-finished build or one built for a
different APK:

### 4.1 The manifest file (direct disk read, for offline checks before/after transfer)

`<inputHash>.manifest.json` is written automatically during warmup on the
large-heap machine (persisted once shard index construction finishes,
best-effort, doesn't block the main warmup flow). Example content:

```json
{
  "tool_version": "20260721.3",
  "input_hash": "42e32dbd...",
  "total_classes": 237931,
  "shard_count": 42,
  "shard_covered_classes": 231500,
  "built_at_epoch_ms": 1753900800000,
  "note": "Prebaked index volume — copy the whole --index-dir to another machine and start it against the same APK (same input_hash) for FAST_RESTORE."
}
```

Diff this file before/after transfer, or use it to confirm:

- `input_hash` matches the hash computed from the APK loaded on the target
  machine (otherwise this volume was built for a different APK and can't be
  reused)
- `shard_count > 0` and `shard_covered_classes` is close to `total_classes`
  (otherwise that warmup on the large-heap machine may have been cut short
  by memory pressure, leaving incomplete coverage)
- `tool_version` matches the version running on the target machine (index
  format compatibility isn't guaranteed across versions)

### 4.2 The `index_prebaked` section of `/index-stats` (runtime check, usable by AI or ops)

Once the service is up on the low-spec machine, call:

```bash
curl -s http://localhost:18650/index-stats | jq '.index_prebaked'
```

- `complete: true` — the manifest exists and its `input_hash` matches the
  currently loaded APK; this index volume can be trusted as fully usable for
  `FAST_RESTORE`. The response also includes every manifest field
  (`shard_count`, etc.) for further verification.
- `complete: false` — one of three reasons (explained in the `reason`
  field):
  1. No warmup has run yet for this APK
  2. The manifest file doesn't exist — shard construction hasn't finished,
     or this is an older index volume built before the manifest feature
     existed
  3. The manifest's `input_hash` doesn't match the currently loaded APK —
     this volume was built for a different APK

`index_prebaked` and the `shard_index` field already present in the same
response (`ContentShardIndex.getStats()`, runtime in-memory coverage) are
two complementary views: `shard_index` shows "what this machine currently
has loaded in memory", while `index_prebaked` shows "whether this on-disk
volume was completely baked when it was built" — right after unpacking on
the low-spec machine, before any in-memory load has been triggered, the
latter is the only signal available to verify ahead of time.
