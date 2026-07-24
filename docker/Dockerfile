# delamain (fused): single image, single exposed port (8651).
#
# Java backend (com.zin.delamain.Main) binds 127.0.0.1:8650 inside the
# container only — never exposed to the host. The Python gateway binds
# 0.0.0.0:8651 and is the only reachable port. entrypoint.sh starts both
# processes and proxies the gateway's stdin/PID1 signal handling to Java.
#
# Runtime is Azul Zulu 25 (LTS) on Debian bookworm (glibc). bookworm ships
# python3.11, which the py-deps stage matches so the gateway's compiled wheels
# (pydantic-core, etc.) are ABI-compatible.

# ---------------------------------------------------------------------------
# Stage 1: java-builder — compile the fat JAR + install jadx-all into m2.
# Azul Zulu 25 JDK (Debian bookworm). Maven is downloaded as a binary so apt
# does not pull a second (OpenJDK) JVM in as a `maven` package dependency.
# ---------------------------------------------------------------------------
FROM azul/zulu-openjdk-debian:25.0.3-jdk AS java-builder
WORKDIR /build

ARG MAVEN_VERSION=3.9.9
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates unzip; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
         -o /tmp/maven.tar.gz; \
    tar -xzf /tmp/maven.tar.gz -C /opt; \
    ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn; \
    rm -f /tmp/maven.tar.gz; \
    mvn -v

# jadx-all is NOT published to Maven Central (only jadx-core / jadx-cli etc. are).
# Pull the official release zip and install jadx-all into the builder's local m2.
ARG JADX_VERSION=1.5.6
ARG JADX_ZIP_SHA256=545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974
RUN set -eux; \
    curl -fSL --retry 5 --retry-delay 3 --retry-all-errors \
         "https://github.com/skylot/jadx/releases/download/v${JADX_VERSION}/jadx-${JADX_VERSION}.zip" \
         -o /tmp/jadx.zip; \
    echo "${JADX_ZIP_SHA256}  /tmp/jadx.zip" | sha256sum -c -; \
    unzip -q /tmp/jadx.zip -d /tmp/jadx; \
    JADX_ALL=$(find /tmp/jadx/lib -type f -name "jadx-*-all.jar" -print -quit); \
    test -n "${JADX_ALL}"; \
    mvn -q install:install-file \
        -Dfile="${JADX_ALL}" \
        -DgroupId=io.github.skylot \
        -DartifactId=jadx-all \
        -Dversion=${JADX_VERSION} \
        -Dpackaging=jar; \
    rm -rf /tmp/jadx /tmp/jadx.zip

COPY pom.xml .
RUN mvn -DskipTests dependency:resolve -q 2>/dev/null || true
COPY src/ src/
# Repo-root VERSION is the single source of truth AppVersion.java reads off the
# classpath (maven-resources-plugin copies it into the shaded jar as /VERSION).
COPY VERSION .
RUN mvn -DskipTests clean package -q

# ---------------------------------------------------------------------------
# Stage 2: py-deps — resolve gateway Python dependencies into a target dir.
# python:3.11-slim-bookworm — matches the runtime's python3.11 (Azul Zulu on
# bookworm) so pydantic-core and other compiled wheels are ABI-compatible.
# ---------------------------------------------------------------------------
FROM python:3.11-slim-bookworm AS py-deps
WORKDIR /build
COPY gateway/requirements.lock ./
RUN pip3 install --no-cache-dir --require-hashes --target=/pydeps -r requirements.lock && \
    rm -rf /root/.cache

# ---------------------------------------------------------------------------
# Stage 3: runtime — Azul Zulu 25 JRE (Debian bookworm, glibc) + python3.11.
# ---------------------------------------------------------------------------
FROM azul/zulu-openjdk-debian:25.0.3-jre

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

# Version single source of truth: banner.py resolves /app/VERSION.
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
