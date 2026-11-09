# =========================================================
# Hierograph MCP server runtime image
#
# A self-contained container: the target project and the
# host's Maven directory are mounted read-only, everything
# else happens inside — producing and regenerating the build
# manifest (Maven + the Hierograph plugin, baked in),
# scanning, and serving MCP.
#
#   docker run -p 8080:8080 \
#     -v /path/to/project:/workspace:ro \
#     -v ~/.m2:/m2:ro \
#     [-v hierograph-state:/hierograph] \
#     <image>
#
# The build context is staged by the `docker` profile:
# JAR_FILE (the repackaged server), repository/ (the
# Hierograph plugin and the io.hierograph artifacts it needs,
# in local-repository layout) and entrypoint.sh.
# =========================================================

# --- Stage 1: complete the plugin repository ---------------
# Adds the plugin's third-party dependencies (and their parent
# POMs) to the staged repository, and supplies the Maven
# distribution for the final image.
FROM maven:3.9.9-eclipse-temurin-21 AS plugin-repository
ARG HIEROGRAPH_VERSION
COPY repository /opt/hierograph/repository
RUN mvn -B -q -Dmaven.repo.local=/opt/hierograph/repository \
      org.apache.maven.plugins:maven-dependency-plugin:3.11.0:get \
      -Dartifact=io.hierograph:hierograph-maven-plugin:${HIEROGRAPH_VERSION}

# --- Stage 2: runtime --------------------------------------
FROM eclipse-temurin:21-jre
ARG JAR_FILE
ARG HIEROGRAPH_VERSION

# Maven runs on the JRE: regeneration never compiles.
COPY --from=plugin-repository /usr/share/maven /opt/maven
# A read-only tail repository: the plugin and its dependencies.
COPY --from=plugin-repository /opt/hierograph/repository /opt/hierograph/repository
ENV PATH=/opt/maven/bin:$PATH \
    HIEROGRAPH_VERSION=${HIEROGRAPH_VERSION}

COPY ${JAR_FILE} /app/app.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh

# Spring MVC (Jetty) HTTP port — serves the MCP STREAMABLE
# endpoint and the GraphQL/GraphiQL UI.
EXPOSE 8080

# /workspace: the project (read-only); /m2: the host's ~/.m2
# (read-only); /hierograph: the state (optionally a volume).
WORKDIR /workspace

ENTRYPOINT ["/app/entrypoint.sh"]
