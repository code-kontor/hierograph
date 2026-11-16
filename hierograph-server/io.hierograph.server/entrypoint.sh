#!/bin/sh
# Hierograph MCP server — container entrypoint.
#
# Wires the run contract: the project at /workspace (read-only) and the state at $HIEROGRAPH_STATE_DIR (default
# /hierograph; a volume keeps it across restarts). Everything the server writes lands in the state directory.
#
# - A Maven build: the host's ~/.m2 read-only at /m2. The manifest is produced and regenerated in here with the
#   baked-in Maven and Hierograph plugin, without compiling and without writing into /workspace or /m2.
# - A Gradle build: the host writes the manifest (`./gradlew hierographManifest`, by default into
#   /workspace/.hierograph). A Gradle build definition is a program that needs the host's JDKs, credentials and init
#   scripts, so it is not run in here. The server maps the manifest's host paths to /workspace and, as it cannot
#   rerun the host's Gradle, reports edited build files as stale; it reloads when the host rewrites the manifest.
#
# The build tool: Maven when /workspace holds a pom.xml, otherwise Gradle when it holds a settings or build
# script; HIEROGRAPH_BUILD_TOOL overrides this (a build with both is treated as Maven by default).
#
# Environment:
#   HIEROGRAPH_BUILD_TOOL   maven | gradle (default: detected)
#   HIEROGRAPH_MAVEN_ARGS   extra Maven arguments for (re)generating the manifest, e.g. "-Pitest"
#   HIEROGRAPH_MANIFEST     Gradle: the manifest in the container (default /workspace/.hierograph/manifest.json)
#   HIEROGRAPH_STATE_DIR    state directory (default /hierograph)
#   HIEROGRAPH_BUILD_FILES  reaction to edited build files: regenerate (Maven default) | notify (Gradle default) | off
#   HIEROGRAPH_URL          the MCP URL as the host reaches it, e.g. http://localhost:8765/mcp; reported by
#                           server_info and the runtime file instead of the container's own address
#   HIEROGRAPH_WORKSPACE    the project's path on the host; reported as the workspace instead of /workspace
#   HIEROGRAPH_JAVA_OPTS    JVM options of the server (the image sets -Xmx2g); not passed to Maven
# Extra arguments to `docker run … <image> <args>` are passed to the server (e.g. --server.port=9090).
set -e

STATE="${HIEROGRAPH_STATE_DIR:-/hierograph}"
WORKSPACE=/workspace

has_gradle_build() {
  for f in settings.gradle settings.gradle.kts build.gradle build.gradle.kts; do
    [ -f "$WORKSPACE/$f" ] && return 0
  done
  return 1
}

TOOL="${HIEROGRAPH_BUILD_TOOL:-}"
if [ -z "$TOOL" ]; then
  if [ -f "$WORKSPACE/pom.xml" ]; then
    TOOL=maven
  elif has_gradle_build; then
    TOOL=gradle
  else
    echo "hierograph: no pom.xml and no settings.gradle(.kts) or build.gradle(.kts) in $WORKSPACE — mount the project's build root: -v /path/to/project:$WORKSPACE:ro" >&2
    exit 1
  fi
fi

case "$TOOL" in
  maven)
    if [ ! -f "$WORKSPACE/pom.xml" ]; then
      echo "hierograph: HIEROGRAPH_BUILD_TOOL=maven, but there is no pom.xml in $WORKSPACE" >&2
      exit 1
    fi
    mkdir -p "$STATE/m2/repository"
    # Maven's user home (wrapper downloads, settings lookups) must be writable too.
    export HOME="$STATE/home"
    mkdir -p "$HOME"

    # Chained local repository: write to the state's front repository, read the baked-in plugin repository and
    # the host's repository as read-only tails.
    TAIL=/opt/hierograph/repository
    [ -d /m2/repository ] && TAIL="$TAIL,/m2/repository"

    SETTINGS=""
    [ -f /m2/settings.xml ] && SETTINGS="-s /m2/settings.xml"
    [ -f /m2/settings-security.xml ] && SETTINGS="$SETTINGS -Dsettings.security=/m2/settings-security.xml"

    MANIFEST="$STATE/manifest.json"
    BUILD_FILES="${HIEROGRAPH_BUILD_FILES:-regenerate}"
    set -- --hierograph.graph.regenerate.command="mvn -B -q -f $WORKSPACE/pom.xml -Dmaven.repo.local=$STATE/m2/repository -Dmaven.repo.local.tail=$TAIL $SETTINGS ${HIEROGRAPH_MAVEN_ARGS:-} io.hierograph:hierograph-maven-plugin:$HIEROGRAPH_VERSION:manifest -Dhierograph.manifestDir=$STATE" "$@"
    ;;
  gradle)
    if ! has_gradle_build; then
      echo "hierograph: HIEROGRAPH_BUILD_TOOL=gradle, but there is no settings.gradle(.kts) or build.gradle(.kts) in $WORKSPACE" >&2
      exit 1
    fi
    MANIFEST="${HIEROGRAPH_MANIFEST:-$WORKSPACE/.hierograph/manifest.json}"
    if [ ! -f "$MANIFEST" ]; then
      echo "hierograph: no manifest at $MANIFEST. A Gradle build writes it on the host: ./gradlew --init-script hierograph.init.gradle hierographManifest (the init script ships in the Hierograph Gradle plugin jar); then start the container again" >&2
      exit 1
    fi
    BUILD_FILES="${HIEROGRAPH_BUILD_FILES:-notify}"
    ;;
  *)
    echo "hierograph: unknown HIEROGRAPH_BUILD_TOOL '$TOOL' (expected maven or gradle)" >&2
    exit 1
    ;;
esac

# Unquoted on purpose: the options split into separate arguments.
exec java ${HIEROGRAPH_JAVA_OPTS:-} -jar /app/app.jar \
  --hierograph.graph.manifest="$MANIFEST" \
  --hierograph.graph.stateDir="$STATE" \
  --hierograph.graph.buildRoot="$WORKSPACE" \
  --hierograph.graph.watch.build-files="$BUILD_FILES" \
  --hierograph.server.url="${HIEROGRAPH_URL:-}" \
  --hierograph.server.workspace="${HIEROGRAPH_WORKSPACE:-}" \
  "$@"
