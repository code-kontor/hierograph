#!/usr/bin/env bash
#
# Download the Spring Framework jars into a dedicated directory.
# Usage: ./download-jars.sh [target-dir]   (default: ./jars)
#
set -euo pipefail

SPRING_VERSION="${SPRING_VERSION:-7.0.8}"
BASE="https://repo1.maven.org/maven2/org/springframework"
TARGET_DIR="${1:-jars}"

MODULES="spring-aop spring-aspects spring-beans spring-context \
         spring-context-indexer spring-context-support spring-core \
         spring-expression spring-instrument spring-jdbc \
         spring-jms spring-messaging spring-orm spring-oxm spring-r2dbc \
         spring-test spring-tx spring-web spring-webflux spring-webmvc \
         spring-websocket"

mkdir -p "$TARGET_DIR"
for m in $MODULES; do
    echo "${BASE}/${m}/${SPRING_VERSION}/${m}-${SPRING_VERSION}.jar"
    curl -fsSL -o "${TARGET_DIR}/${m}-${SPRING_VERSION}.jar" \
        "${BASE}/${m}/${SPRING_VERSION}/${m}-${SPRING_VERSION}.jar"
done

echo "Downloaded $(echo "$MODULES" | wc -w | tr -d ' ') jars (Spring ${SPRING_VERSION}) into ${TARGET_DIR}/"
