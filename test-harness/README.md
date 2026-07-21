# test-harness — delamain local regression harness

Runs end-to-end regression against a live Java instance using a "controlled R8-obfuscated
sample" (ground-truth) plus a "real-world obfuscated APK". This is the machine-verifiable
acceptance gate for P0 fixes.

## Layout

```
test-harness/
├── validate.py              ← regression script (no third-party deps, urllib only), 14 assertions
├── acme-obf.dex             ← controlled R8-obfuscated sample (prebuilt)
├── mapping.txt              ← ground-truth obfuscation mapping for the acme sample
├── rules.pro                ← R8 keep rules used to produce acme-obf.dex (-dontshrink -dontoptimize)
├── src/com/acme/demo/*.java ← acme sample source (inner classes/anonymous inner classes/native/unique string markers)
└── real/
    ├── UnCrackable-Level1.apk  (66KB)
    └── UnCrackable-Level2.apk  (901KB, 283 classes/638 incl. inner classes, ships libfoo.so)
```

`real/UnCrackable-Level1.apk` and `real/UnCrackable-Level2.apk` are the public OWASP MASTG
"UnCrackable" CrackMe samples (Apache-2.0), redistributed here unmodified for regression
testing. Source: https://github.com/OWASP/owasp-mastg

`acme-obf.dex` ground-truth mapping: `CreditCard→c`, `CreditCard$Metadata→a`,
`CreditCard$Validator→b`, `CryptoUtils→d`, `PaymentProcessor`→kept as-is,
`PaymentProcessor$1→e`, `SecretKeeper→g`, `SecretKeeper$VaultEntry→f`.

## Running it

Build (~4.5s, `jadx-all-1.5.6.jar` already present under `.docker-build/`):

```bash
mvn -q --batch-mode -DskipTests package
```

Start two local instances (`<repo>` = repo root, `<tmp>` = any scratch working directory):

```bash
# A: controlled R8 sample (ground-truth, port 28650)
java -Xmx4g -cp "target/delamain.jar:.docker-build/jadx-all-1.5.6.jar" \
  com.zin.delamain.Main --port 28650 --bind 127.0.0.1 --auth-token test-token \
  --apk test-harness/acme-obf.dex --output-dir <tmp>/outA --index-dir <tmp>/idxA --workers 2 &

# B: real-world obfuscated APK, UnCrackable-Level2 (port 28651)
java -Xmx4g -cp "target/delamain.jar:.docker-build/jadx-all-1.5.6.jar" \
  com.zin.delamain.Main --port 28651 --bind 127.0.0.1 --auth-token test-token \
  --apk test-harness/real/UnCrackable-Level2.apk --output-dir <tmp>/outB --index-dir <tmp>/idxB --workers 2 &
```

Run the regression gate (once both instances have finished warmup):

```bash
CTRL_URL=http://127.0.0.1:28650 REAL_URL=http://127.0.0.1:28651 \
JADX_AUTH_TOKEN=test-token python3 test-harness/validate.py
```

## Acceptance contract

- Exit code `0` = no FAIL.
- `[BUG-PRESENT]` marks a known-defect live marker. Once the corresponding fix lands it
  must flip to `[PASS/FIXED]`, with zero FAIL across all `check()` assertions — that is
  the P0 pass criterion.

## Rebuilding acme-obf.dex (optional, only needed if the sample source changes)

`acme-obf.dex` is prebuilt and ready to use as-is. It only needs rebuilding after editing
`src/com/acme/demo/*.java`: `javac` compile → `d8`/`r8` (Android build-tools) with
`rules.pro` (`-dontshrink -dontoptimize` keeps obfuscation on and tree-shaking off, so all
8 classes are retained) → produces `acme-obf.dex` + `mapping.txt`.
`--release` plus `-dontshrink -dontoptimize` is the required combination (`--debug` turns
obfuscation off and yields an identity mapping).
