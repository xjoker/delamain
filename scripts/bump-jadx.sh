#!/usr/bin/env bash
# One-shot jadx version bump: download the release zip, compute SHA256, install
# into the local m2, and replace all version references in place.
#
# Usage: scripts/bump-jadx.sh <new-version>   e.g. scripts/bump-jadx.sh 1.5.7
#
# This script only does: download + checksum verify + m2 install + text replace.
# It does not commit, does not run mvn test, and does not touch git.
# After it finishes, run `mvn test` yourself to verify compatibility, then commit manually.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ---------- 0. Validate arguments ----------
if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <new-version>  e.g. $0 1.5.7" >&2
    exit 1
fi

NEW_VERSION="$1"

if [[ ! "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Error: version should be formatted X.Y.Z, got: $NEW_VERSION" >&2
    exit 1
fi

if [[ ! -f "$REPO_ROOT/pom.xml" ]]; then
    echo "Error: pom.xml not found at repo root (resolved repo root: $REPO_ROOT)" >&2
    exit 1
fi

# Current version (single source of truth = pom.xml's <jadx.version>)
OLD_VERSION="$(sed -n 's/.*<jadx\.version>\(.*\)<\/jadx\.version>.*/\1/p' "$REPO_ROOT/pom.xml")"
if [[ -z "$OLD_VERSION" ]]; then
    echo "Error: could not parse the current <jadx.version> from pom.xml" >&2
    exit 1
fi

if [[ "$OLD_VERSION" == "$NEW_VERSION" ]]; then
    echo "Error: new version ($NEW_VERSION) is the same as the current version, nothing to bump" >&2
    exit 1
fi

echo "Current version: $OLD_VERSION -> target version: $NEW_VERSION"

# ---------- 1. Prepare temp dir + download ----------
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bump-jadx.XXXXXX")"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

ZIP_URL="https://github.com/skylot/jadx/releases/download/v${NEW_VERSION}/jadx-${NEW_VERSION}.zip"
ZIP_PATH="$TMP_DIR/jadx-${NEW_VERSION}.zip"

echo "Downloading $ZIP_URL ..."
if ! curl -fSL --retry 3 -o "$ZIP_PATH" "$ZIP_URL"; then
    echo "Error: download failed, please confirm version $NEW_VERSION exists at https://github.com/skylot/jadx/releases" >&2
    exit 1
fi

# ---------- 2. Compute SHA256 ----------
if command -v sha256sum >/dev/null 2>&1; then
    NEW_SHA256="$(sha256sum "$ZIP_PATH" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
    NEW_SHA256="$(shasum -a 256 "$ZIP_PATH" | awk '{print $1}')"
else
    echo "Error: neither sha256sum nor shasum command found" >&2
    exit 1
fi
echo "SHA256: $NEW_SHA256"

# ---------- 3. Unzip + install into local m2 + copy to .docker-build ----------
UNZIP_DIR="$TMP_DIR/unzipped"
mkdir -p "$UNZIP_DIR"
if ! unzip -q "$ZIP_PATH" -d "$UNZIP_DIR"; then
    echo "Error: failed to unzip $ZIP_PATH" >&2
    exit 1
fi

JAR_PATH="$(find "$UNZIP_DIR/lib" -maxdepth 1 -name 'jadx-*-all.jar' | head -n1)"
if [[ -z "$JAR_PATH" || ! -f "$JAR_PATH" ]]; then
    echo "Error: jadx-*-all.jar not found under the extracted lib/" >&2
    exit 1
fi
echo "Found jar: $JAR_PATH"

if ! mvn -q install:install-file \
    -Dfile="$JAR_PATH" \
    -DgroupId=io.github.skylot \
    -DartifactId=jadx-all \
    -Dversion="$NEW_VERSION" \
    -Dpackaging=jar; then
    echo "Error: mvn install:install-file failed to install into local m2" >&2
    exit 1
fi
echo "Installed into local m2: io.github.skylot:jadx-all:$NEW_VERSION"

mkdir -p "$REPO_ROOT/.docker-build"
cp "$JAR_PATH" "$REPO_ROOT/.docker-build/jadx-all-${NEW_VERSION}.jar"
echo "Copied to .docker-build/jadx-all-${NEW_VERSION}.jar"

# ---------- 4. Replace version references in place ----------
CHANGED_FILES=()

# Cross-platform sed -i wrapper (GNU sed and BSD/macOS sed take -i differently)
sed_inplace() {
    if sed --version >/dev/null 2>&1; then
        sed -i "$@"          # GNU sed
    else
        sed -i '' "$@"       # BSD/macOS sed
    fi
}

# Compare before/after to check the replacement actually hit; warn if not (never fail silently)
warn_if_unchanged() {
    local file="$1"
    if cmp -s "$file" "$file.bumpjadx.orig"; then
        echo "Warning: $file had no replacements applied (pattern may not have matched, please check manually)" >&2
    else
        CHANGED_FILES+=("$file")
    fi
    rm -f "$file.bumpjadx.orig"
}

# --- pom.xml ---
cp "$REPO_ROOT/pom.xml" "$REPO_ROOT/pom.xml.bumpjadx.orig"
sed_inplace "s/<jadx\.version>${OLD_VERSION}<\/jadx\.version>/<jadx.version>${NEW_VERSION}<\/jadx.version>/" \
    "$REPO_ROOT/pom.xml"
warn_if_unchanged "$REPO_ROOT/pom.xml"

# --- Dockerfiles ---
for DOCKERFILE in docker/Dockerfile docker/Dockerfile.local; do
    [[ -f "$REPO_ROOT/$DOCKERFILE" ]] || { echo "Warning: $DOCKERFILE does not exist, skipping" >&2; continue; }
    cp "$REPO_ROOT/$DOCKERFILE" "$REPO_ROOT/$DOCKERFILE.bumpjadx.orig"
    sed_inplace \
        -e "s/^ARG JADX_VERSION=.*/ARG JADX_VERSION=${NEW_VERSION}/" \
        -e "s/^ARG JADX_ZIP_SHA256=.*/ARG JADX_ZIP_SHA256=${NEW_SHA256}/" \
        -e "s#jadx-all/${OLD_VERSION}/jadx-all-${OLD_VERSION}\.jar#jadx-all/${NEW_VERSION}/jadx-all-${NEW_VERSION}.jar#g" \
        -e "s/jadx-all-${OLD_VERSION}\.jar/jadx-all-${NEW_VERSION}.jar/g" \
        "$REPO_ROOT/$DOCKERFILE"
    warn_if_unchanged "$REPO_ROOT/$DOCKERFILE"
done

# --- README / docs (version strings + jar paths) ---
for DOC in README.md docs/dev-guide.md test-harness/README.md; do
    [[ -f "$REPO_ROOT/$DOC" ]] || { echo "Warning: $DOC does not exist, skipping" >&2; continue; }
    cp "$REPO_ROOT/$DOC" "$REPO_ROOT/$DOC.bumpjadx.orig"
    sed_inplace \
        -e "s#v${OLD_VERSION}/jadx-${OLD_VERSION}\.zip#v${NEW_VERSION}/jadx-${NEW_VERSION}.zip#g" \
        -e "s/-Dversion=${OLD_VERSION}/-Dversion=${NEW_VERSION}/g" \
        -e "s/jadx-all-${OLD_VERSION}\.jar/jadx-all-${NEW_VERSION}.jar/g" \
        "$REPO_ROOT/$DOC"
    warn_if_unchanged "$REPO_ROOT/$DOC"
done

# ---------- 5. Summary ----------
echo
echo "========== jadx version bump summary =========="
echo "Old version: $OLD_VERSION"
echo "New version: $NEW_VERSION"
echo "New SHA256: $NEW_SHA256"
echo "Local m2: io.github.skylot:jadx-all:$NEW_VERSION"
echo ".docker-build/jadx-all-${NEW_VERSION}.jar is ready"
echo "Changed files:"
for f in "${CHANGED_FILES[@]}"; do
    echo "  - $f"
done
echo "========================================"
echo
echo "Next step: run mvn test to verify compatibility, then manually git add + commit once confirmed (this script does not commit automatically)."
