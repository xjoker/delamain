# delamain (fused): single image, single exposed port (8651).
#
# Java backend (com.zin.delamain.Main) binds 127.0.0.1:8650 inside the
# container only — never exposed to the host. The Python gateway binds
# 0.0.0.0:8651 and is the only reachable port. entrypoint.sh starts both
# processes and proxies the gateway's stdin/PID1 signal handling to Java.

# ---------------------------------------------------------------------------
# Stage 1: java-builder — compile the fat JAR + install jadx-all into m2.
# maven:3.9.16-eclipse-temurin-21-alpine (linux/amd64, linux/arm64)
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21-alpine@sha256:b1603d5fa3278d21d34630371e783003c554448032afb618c1bb8f7dd603757e AS java-builder
WORKDIR /build

# jadx-all is NOT published to Maven Central (only jadx-core / jadx-cli etc. are).
# Pull the official release zip and install jadx-all into the builder's local m2.
ARG JADX_VERSION=1.5.6
ARG JADX_ZIP_SHA256=545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974
RUN set -eux; \
    apk add --no-cache wget unzip; \
    wget -q "https://github.com/skylot/jadx/releases/download/v${JADX_VERSION}/jadx-${JADX_VERSION}.zip" \
         -O /tmp/jadx.zip; \
    echo "${JADX_ZIP_SHA256}  /tmp/jadx.zip" | sha256sum -c -; \
    unzip -q /tmp/jadx.zip -d /tmp/jadx && \
    JADX_ALL=$(find /tmp/jadx/lib -type f -name "jadx-*-all.jar" -print -quit) && \
    test -n "${JADX_ALL}" && \
    mvn -q install:install-file \
        -Dfile="${JADX_ALL}" \
        -DgroupId=io.github.skylot \
        -DartifactId=jadx-all \
        -Dversion=${JADX_VERSION} \
        -Dpackaging=jar && \
    rm -rf /tmp/jadx /tmp/jadx.zip && \
    apk del wget unzip

COPY pom.xml .
RUN mvn -DskipTests dependency:resolve -q 2>/dev/null || true
COPY src/ src/
RUN mvn -DskipTests clean package -q

# ---------------------------------------------------------------------------
# Stage 2: py-deps — resolve gateway Python dependencies into a target dir
# (no pip cache, no compiler toolchain retained in the runtime stage).
# python:3.12.13-slim-trixie (Debian glibc, matches noble's py3.12 ABI closely
# enough for pure-Python + manylinux wheels used by the gateway).
# ---------------------------------------------------------------------------
FROM python:3.12-slim@sha256:57cd7c3a7a273101a6485ba99423ee568157882804b1124b4dd04266317710de AS py-deps
WORKDIR /build
COPY gateway/requirements.lock ./
RUN pip3 install --no-cache-dir --require-hashes --target=/pydeps -r requirements.lock && \
    rm -rf /root/.cache

# ---------------------------------------------------------------------------
# Stage 3: runtime — JRE + python3 interpreter (no pip/build tools), glibc.
# eclipse-temurin:21.0.11_10-jre-noble (Ubuntu 24.04, glibc, ships python3.12
# via apt so gateway's --target=/pydeps site-packages are ABI-compatible).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 && \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/*

WORKDIR /app

# Java fat JAR (jadx-all excluded from shade — provided via separate copy below)
COPY --from=java-builder /build/target/delamain.jar /app/delamain.jar

# jadx-all jar from the builder's m2 (installed via install:install-file).
COPY --from=java-builder \
    /root/.m2/repository/io/github/skylot/jadx-all/1.5.6/jadx-all-1.5.6.jar \
    /app/lib/jadx-all-1.5.6.jar

# Gateway Python dependencies (resolved in stage 2, no pip/build tools here).
COPY --from=py-deps /pydeps /app/pydeps

# Gateway source.
COPY gateway/pyproject.toml /app/gateway/pyproject.toml
COPY gateway/main.py /app/gateway/main.py
COPY gateway/src/ /app/gateway/src/

# Version single source of truth: banner.py resolves /app/VERSION
# (== Path(gateway/src/banner.py).parent.parent.parent / "VERSION"), so /health
# and the startup banner report the repo-root VERSION instead of the fallback.
COPY VERSION /app/VERSION

# Entrypoint orchestrates: start Java on 127.0.0.1:8650 (internal only),
# wait for its /health, then exec the gateway in the foreground on 0.0.0.0:8651.
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Use a stable, unprivileged UID. Empty named volumes inherit these permissions
# when Docker initializes them; /apks is intentionally not owned by this user
# because it is supplied as a read-only bind mount.
RUN groupadd -g 10001 jadx && \
    useradd -u 10001 -g jadx -M -s /usr/sbin/nologin jadx && \
    mkdir -p /data/apks /data/output /data/indices /home/jadx /apks && \
    chown -R jadx:jadx /app /data /home/jadx

VOLUME ["/data/output", "/data/indices"]

ENV HOME=/home/jadx \
    TZ=UTC \
    PYTHONPATH=/app/pydeps \
    PYTHONUNBUFFERED=1

# Only the gateway port is reachable — Java's 8650 is bound to 127.0.0.1
# inside the container by entrypoint.sh and is never EXPOSEd.
EXPOSE 8651

USER 10001:10001

ENTRYPOINT ["/app/entrypoint.sh"]
