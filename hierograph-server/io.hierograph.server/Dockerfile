# =========================================================
# Hierograph MCP server runtime image
#
# Packages the repackaged Spring Boot application as a
# self-contained, runnable container. The jar is built by
# Maven (spring-boot:repackage) and staged into the build
# context by the `docker` profile; JAR_FILE names it.
# =========================================================
FROM eclipse-temurin:21-jre
ARG JAR_FILE

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar

# Spring MVC (Jetty) HTTP port — serves the MCP STREAMABLE
# endpoint and the GraphQL/GraphiQL UI.
EXPOSE 8080

# The server connects to a jQAssistant/Neo4j store over Bolt.
# The default targets the container's own localhost, which is
# rarely correct; override at run time, e.g.
#   docker run -e HIEROGRAPH_BOLT_URI=bolt://host.docker.internal:7687 ...
ENV HIEROGRAPH_BOLT_URI=bolt://localhost:7687

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
