#!/bin/sh
# Hierograph MCP server — container entrypoint.
#
# Wires the run contract: the project at /workspace (read-only), the host's ~/.m2 at /m2 (read-only), and the
# state at $HIEROGRAPH_STATE_DIR (default /hierograph; a volume keeps it across restarts). Everything the
# server writes lands in the state directory. The manifest is produced and regenerated in here with the baked-in
# Maven and Hierograph plugin, without compiling and without writing into /workspace or /m2.
#
# Environment:
#   HIEROGRAPH_MAVEN_ARGS   extra Maven arguments for (re)generating the manifest, e.g. "-Pitest"
#   HIEROGRAPH_STATE_DIR    state directory (default /hierograph)
#   HIEROGRAPH_BUILD_FILES  reaction to edited POMs: regenerate (default) | notify | off
# Extra arguments to `docker run … <image> <args>` are passed to the server (e.g. --server.port=9090).
set -e

STATE="${HIEROGRAPH_STATE_DIR:-/hierograph}"
WORKSPACE=/workspace

if [ ! -f "$WORKSPACE/pom.xml" ]; then
  echo "hierograph: no pom.xml in $WORKSPACE — mount the project's build root: -v /path/to/project:$WORKSPACE:ro" >&2
  exit 1
fi

mkdir -p "$STATE/m2/repository"
# Maven's user home (wrapper downloads, settings lookups) must be writable too.
export HOME="$STATE/home"
mkdir -p "$HOME"

# Chained local repository: write to the state's front repository, read the baked-in plugin repository and the
# host's repository as read-only tails.
TAIL=/opt/hierograph/repository
[ -d /m2/repository ] && TAIL="$TAIL,/m2/repository"

SETTINGS=""
[ -f /m2/settings.xml ] && SETTINGS="-s /m2/settings.xml"
[ -f /m2/settings-security.xml ] && SETTINGS="$SETTINGS -Dsettings.security=/m2/settings-security.xml"

REGENERATE="mvn -B -q -f $WORKSPACE/pom.xml -Dmaven.repo.local=$STATE/m2/repository -Dmaven.repo.local.tail=$TAIL $SETTINGS ${HIEROGRAPH_MAVEN_ARGS:-} io.hierograph:hierograph-maven-plugin:$HIEROGRAPH_VERSION:manifest -Dhierograph.manifestDir=$STATE"

exec java -jar /app/app.jar \
  --hierograph.graph.manifest="$STATE/manifest.json" \
  --hierograph.graph.stateDir="$STATE" \
  --hierograph.graph.buildRoot="$WORKSPACE" \
  --hierograph.graph.watch.build-files="${HIEROGRAPH_BUILD_FILES:-regenerate}" \
  --hierograph.graph.regenerate.command="$REGENERATE" \
  "$@"
