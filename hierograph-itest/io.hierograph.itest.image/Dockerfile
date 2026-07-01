# =========================================================
# Base: Temurin JRE + jQAssistant CLI (shared by builder and runtime)
# Downloaded once, reused by both downstream stages.
# =========================================================
FROM eclipse-temurin:21-jre AS jqassistant
ARG JQA_VERSION=2.9.1

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
 && curl -fsSL -o /tmp/jqa.zip \
      "https://github.com/jQAssistant/jqassistant/releases/download/${JQA_VERSION}/jqassistant-commandline-neo4jv5-${JQA_VERSION}-distribution.zip" \
 && unzip /tmp/jqa.zip -d /opt \
 && mv /opt/jqassistant-commandline-neo4jv5-${JQA_VERSION} /opt/jqassistant \
 && rm /tmp/jqa.zip \
 && rm -rf /var/lib/apt/lists/*

# =========================================================
# Stage: download Spring Framework jars, scan, analyze
# =========================================================
FROM jqassistant AS builder
ARG SPRING_VERSION=7.0.8
ARG RULES_GROUP=io.hierograph
ARG RULES_ARTIFACT=io.hierograph.jqassistant.rules
ARG RULES_VERSION=0.2.0-SNAPSHOT

# Download Spring Framework jars from Maven Central
COPY download-jars.sh /usr/local/bin/download-jars.sh
RUN chmod +x /usr/local/bin/download-jars.sh \
 && SPRING_VERSION="${SPRING_VERSION}" /usr/local/bin/download-jars.sh /jars \
 && ls -la /jars

# Install the Hierograph rule-only plugin into jQAssistant's local repository so
# the Maven coordinate declared in .jqassistant.yml resolves offline. A flattened
# POM (no parent, no dependencies) is used so the reactor parent chain is not
# required; the `_remote.repositories` marker tells the resolver it is a local
# install and must not be fetched from a remote repository.
COPY plugin/rules.jar /tmp/rules.jar
RUN set -eu; \
    DIR="/root/.jqassistant/repository/$(echo $RULES_GROUP | tr . /)/$RULES_ARTIFACT/$RULES_VERSION"; \
    mkdir -p "$DIR"; \
    cp /tmp/rules.jar "$DIR/$RULES_ARTIFACT-$RULES_VERSION.jar"; \
    { \
      echo '<project xmlns="http://maven.apache.org/POM/4.0.0">'; \
      echo '  <modelVersion>4.0.0</modelVersion>'; \
      echo "  <groupId>$RULES_GROUP</groupId>"; \
      echo "  <artifactId>$RULES_ARTIFACT</artifactId>"; \
      echo "  <version>$RULES_VERSION</version>"; \
      echo '  <packaging>jar</packaging>'; \
      echo '</project>'; \
    } > "$DIR/$RULES_ARTIFACT-$RULES_VERSION.pom"; \
    printf '%s\n' "$RULES_ARTIFACT-$RULES_VERSION.jar>=" "$RULES_ARTIFACT-$RULES_VERSION.pom>=" > "$DIR/_remote.repositories"; \
    rm /tmp/rules.jar

# Scan & analyze using the project's root .jqassistant.yml (auto-discovered in
# the working directory). The config pins the plugins, rule groups/concepts and
# the store location (file:mcp-example-db).
WORKDIR /project
COPY .jqassistant.yml /project/.jqassistant.yml
RUN /opt/jqassistant/bin/jqassistant.sh scan -f java:classpath::/jars \
 && /opt/jqassistant/bin/jqassistant.sh analyze

# Warm the jQAssistant plugin repository (~/.jqassistant) so the runtime server
# starts fully offline. `server` resolves its complete plugin set on first run;
# feeding it Enter on stdin makes it shut down again immediately after startup.
RUN echo "" | /opt/jqassistant/bin/jqassistant.sh server

# =========================================================
# Runtime: serve the pre-parsed store
# =========================================================
FROM jqassistant
WORKDIR /project
# Pre-parsed store, the analysis config, and the pre-resolved plugin repository
# (including the Hierograph rules plugin) — so the server starts without any
# scan, analysis or artifact download.
COPY --from=builder /project/mcp-example-db /project/mcp-example-db
COPY --from=builder /project/.jqassistant.yml /project/.jqassistant.yml
COPY --from=builder /root/.jqassistant /root/.jqassistant

EXPOSE 7474 7687
# `server` blocks on stdin waiting for Enter and shuts down on EOF; a detached
# container has stdin at EOF, so hold it open to keep the server running until
# the container is stopped. Bind to 0.0.0.0 (default is localhost) so the
# published ports are reachable from outside the container.
CMD ["sh", "-c", "tail -f /dev/null | /opt/jqassistant/bin/jqassistant.sh server -D jqassistant.store.embedded.listen-address=0.0.0.0"]
