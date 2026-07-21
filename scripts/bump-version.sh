#!/usr/bin/env bash
# One-shot bump of this tool's version (version format: YYYYMMDD.N).
#
# The VERSION file is the single source of truth: the runtime version (/health,
# jadx_init) is read directly by Java AppVersion and gateway banner.py, not
# through this script. This script only syncs VERSION to a handful of
# **cosmetic copies** (Maven/Python packaging metadata, example output in docs),
# and greps the whole repo for any straggling old version string —— a
# structural safeguard against "forgot to update one spot".
#
# Usage:
#   scripts/bump-version.sh              # automatic: today's date; .N+1 if VERSION is already today, else .1
#   scripts/bump-version.sh 20260721.3   # explicit override
#
# This script does not commit and does not build. After changing, verify with
# mvn/pytest as needed, then commit manually.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ ! -f VERSION ]]; then
    echo "Error: VERSION file not found at repo root (resolved repo root: $REPO_ROOT)" >&2
    exit 1
fi

OLD="$(tr -d '[:space:]' < VERSION)"
if [[ ! "$OLD" =~ ^[0-9]{8}\.[0-9]+$ ]]; then
    echo "Error: current VERSION content is not in YYYYMMDD.N format: '$OLD'" >&2
    exit 1
fi

# ---------- Compute the new version ----------
if [[ $# -ge 1 ]]; then
    NEW="$1"
    if [[ ! "$NEW" =~ ^[0-9]{8}\.[0-9]+$ ]]; then
        echo "Error: version should be formatted YYYYMMDD.N, got: $NEW" >&2
        exit 1
    fi
else
    TODAY="$(date +%Y%m%d)"
    OLD_DATE="${OLD%%.*}"
    OLD_N="${OLD##*.}"
    if [[ "$OLD_DATE" == "$TODAY" ]]; then
        NEW="${TODAY}.$((OLD_N + 1))"   # increment within the same day
    else
        NEW="${TODAY}.1"                # reset on a new day
    fi
fi

if [[ "$OLD" == "$NEW" ]]; then
    echo "Error: new version is the same as the current one ($OLD), nothing to bump" >&2
    exit 1
fi
echo "Version: $OLD -> $NEW"

# Cross-platform sed -i (GNU vs BSD/macOS)
sed_inplace() {
    if sed --version >/dev/null 2>&1; then sed -i "$@"; else sed -i '' "$@"; fi
}

# ---------- 1. Single source of truth ----------
printf '%s\n' "$NEW" > VERSION

# ---------- 2. Cosmetic copies ----------
# These do not drive the runtime version (the runtime reads the VERSION file);
# they're just packaging metadata / doc examples, kept in sync for consistency.
sed_inplace "s#<version>${OLD}</version>#<version>${NEW}</version>#" pom.xml
sed_inplace "s#^version = \"${OLD}\"#version = \"${NEW}\"#" gateway/pyproject.toml
for DOC in README.md docs/prebaked-index.md; do
    [[ -f "$DOC" ]] && sed_inplace "s#${OLD}#${NEW}#g" "$DOC"
done

# ---------- 3. Straggler detection (repo-wide grep for the old version string) ----------
echo "--- Checking for leftover '$OLD' (should be empty) ---"
STRAGGLERS="$(grep -rn --exclude-dir=.git --exclude-dir=target --exclude-dir=.docker-build \
    --exclude=VERSION --include='*.java' --include='*.py' --include='*.xml' \
    --include='*.toml' --include='*.md' "$OLD" . || true)"
if [[ -n "$STRAGGLERS" ]]; then
    echo "$STRAGGLERS" >&2
    echo "Warning: the old version string still lingers above, please confirm manually whether it needs syncing (safe to ignore if it's test fixture data / unrelated content)." >&2
else
    echo "No stragglers found ✓"
fi

echo
echo "Done: $OLD -> $NEW. Next step: verify with mvn / pytest as needed, then manually git add + commit."
