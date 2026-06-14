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

# Optional: bake in your rule-plugin JARs
# COPY plugins/*.jar /opt/jqassistant/plugins/

# Download Spring Framework jars from Maven Central
COPY download-jars.sh /usr/local/bin/download-jars.sh
RUN chmod +x /usr/local/bin/download-jars.sh \
 && SPRING_VERSION="${SPRING_VERSION}" /usr/local/bin/download-jars.sh /jars \
 && ls -la /jars

# Scan & analyze
WORKDIR /project
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
COPY --from=builder /project/jqassistant /project/jqassistant
# Pre-resolved plugin repository — avoids artifact downloads at server startup.
COPY --from=builder /root/.jqassistant /root/.jqassistant

EXPOSE 7474 7687
CMD ["/opt/jqassistant/bin/jqassistant.sh", "server"]
