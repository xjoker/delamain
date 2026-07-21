#!/bin/sh
# Fused-image entrypoint: starts the Java jadx backend bound to
# 127.0.0.1:8650 (never reachable from outside the container), waits for it
# to become healthy, then runs the Python gateway in the foreground on
# 0.0.0.0:8651 (the only port EXPOSEd/published).
#
# This script stays PID 1 (no exec into the gateway) so it can trap
# SIGTERM/SIGINT and forward them to both children for a clean shutdown --
# otherwise `docker stop` would only reach whichever process happened to be
# PID 1 and the other would be left running/orphaned.
#
# Java <-> gateway auth uses an internal-only token (JADX_INTERNAL_TOKEN env,
# or a random one generated here) that is never exposed to MCP clients. The
# MCP client whitelist is a separate, unrelated env: DELAMAIN_AUTH_TOKENS.
set -eu

JAVA_HEALTH_URL="http://127.0.0.1:8650/health"
JAVA_HEALTH_TIMEOUT_SECS="${JADX_JAVA_STARTUP_TIMEOUT:-120}"

JAVA_PID=""
GATEWAY_PID=""

# --- internal token: reuse if provided, else generate a random one shared
# only between the two processes inside this container. ---
if [ -z "${JADX_INTERNAL_TOKEN:-}" ]; then
    JADX_INTERNAL_TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
fi
export JADX_INTERNAL_TOKEN

shutdown() {
    # Forward the signal to whichever children are still running, then
    # wait for them so we don't return (and let the container exit) before
    # they've actually terminated.
    if [ -n "${GATEWAY_PID}" ] && kill -0 "${GATEWAY_PID}" 2>/dev/null; then
        kill -TERM "${GATEWAY_PID}" 2>/dev/null || true
    fi
    if [ -n "${JAVA_PID}" ] && kill -0 "${JAVA_PID}" 2>/dev/null; then
        kill -TERM "${JAVA_PID}" 2>/dev/null || true
    fi
    [ -n "${GATEWAY_PID}" ] && wait "${GATEWAY_PID}" 2>/dev/null || true
    [ -n "${JAVA_PID}" ] && wait "${JAVA_PID}" 2>/dev/null || true
    exit 0
}
trap shutdown TERM INT

# --- start Java backend in the background, internal-only bind ---
# Resolve the fused jadx-all jar by glob so this script doesn't need to track
# the pinned jadx version (see docker/Dockerfile) and silently break on bump.
JADX_JAR="$(ls /app/lib/jadx-all-*.jar 2>/dev/null | head -1)"
if [ -z "${JADX_JAR}" ]; then
    echo "ERROR: no /app/lib/jadx-all-*.jar found in image" >&2
    exit 1
fi
java -cp "/app/delamain.jar:${JADX_JAR}" \
    com.zin.delamain.Main \
    --port 8650 \
    --bind 127.0.0.1 \
    --auth-token "${JADX_INTERNAL_TOKEN}" \
    --index-dir /data/indices \
    --output-dir /data/output &
JAVA_PID=$!

# --- wait for Java's /health to come up ---
elapsed=0
until python3 -c "
import sys, urllib.request
try:
    urllib.request.urlopen('${JAVA_HEALTH_URL}', timeout=2)
except Exception:
    sys.exit(1)
" >/dev/null 2>&1; do
    if ! kill -0 "${JAVA_PID}" 2>/dev/null; then
        echo "ERROR: Java backend exited before becoming healthy" >&2
        exit 1
    fi
    if [ "${elapsed}" -ge "${JAVA_HEALTH_TIMEOUT_SECS}" ]; then
        echo "ERROR: Java backend did not become healthy within ${JAVA_HEALTH_TIMEOUT_SECS}s" >&2
        kill -TERM "${JAVA_PID}" 2>/dev/null || true
        exit 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
done

# --- optional mounted gateway config (see docker-compose.yml JADX_CONFIG_DIR) ---
GATEWAY_CONFIG_ARGS=""
if [ -f "/data/config/config.toml" ]; then
    GATEWAY_CONFIG_ARGS="--config /data/config/config.toml"
fi

# --- start gateway in the background too (not exec) so this script keeps
# PID 1 and can still trap/forward signals), then wait on it. ---
cd /app/gateway
# shellcheck disable=SC2086
python3 main.py \
    --host 0.0.0.0 \
    --port 8651 \
    --jadx 127.0.0.1:8650 \
    --auth-token "${JADX_INTERNAL_TOKEN}" \
    ${GATEWAY_CONFIG_ARGS} &
GATEWAY_PID=$!

# If the gateway exits on its own (crash or clean shutdown), bring Java down
# too and propagate the gateway's exit code.
set +e
wait "${GATEWAY_PID}"
GATEWAY_EXIT=$?
set -e

if kill -0 "${JAVA_PID}" 2>/dev/null; then
    kill -TERM "${JAVA_PID}" 2>/dev/null || true
    wait "${JAVA_PID}" 2>/dev/null || true
fi

exit "${GATEWAY_EXIT}"
