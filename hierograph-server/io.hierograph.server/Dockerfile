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

COPY ${JAR_FILE} /app/app.jar

# Spring MVC (Jetty) HTTP port — serves the MCP STREAMABLE
# endpoint and the GraphQL/GraphiQL UI.
EXPOSE 8080

# The server reads the target project's build manifest
# (.hierograph/manifest.json, relative to the working directory)
# and scans its compiled classes. Mount the project here:
#   docker run -p 8080:8080 -v /path/to/project:/workspace <image>
# Host paths recorded in the manifest are re-rooted onto the
# mount automatically.
WORKDIR /workspace

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
