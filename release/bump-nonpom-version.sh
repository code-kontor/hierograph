#!/usr/bin/env bash
#
# bump-nonpom-version.sh OLD_VERSION NEW_VERSION
#
# Replaces the Hierograph project version in the NON-POM files that carry it.
# POM files are handled separately by `mvn versions:set` (see RELEASING.md);
# this script covers the references Maven doesn't know about.
#
# Three kinds of reference are handled:
#   1. Verbatim  — files carrying the working version as-is (incl. any -SNAPSHOT).
#                  Plain OLD -> NEW replace. See the FILES list below.
#   2. Release-form manifest — the Claude plugin manifest tracks the RELEASE-form
#                  version only (e.g. "0.2.0", never "0.2.0-SNAPSHOT"), so it is
#                  bumped OLD_REL -> NEW_REL (OLD/NEW with any -SNAPSHOT stripped).
#   3. "Latest release" doc pointers — docs that cite the most recently PUBLISHED
#                  release (MCP image tag, HIEROGRAPH_VERSION, prose). These hold
#                  the PREVIOUS release (not one of this script's arguments), so
#                  each is matched by a stable anchor and set to NEW_REL — but
#                  ONLY on a release step (when NEW is a release version), never
#                  on the "back to -SNAPSHOT" step.
#
# Run from the repo root:
# Example (release):     ./release/bump-nonpom-version.sh 0.1.0-SNAPSHOT 0.1.0
# Example (next dev):    ./release/bump-nonpom-version.sh 0.1.0 0.2.0-SNAPSHOT
#
# Deliberately EXCLUDED:
#   * pom.xml            — owned by mvn versions:set
#   * RELEASING.md       — its version strings are release-process examples
#   * docs/todos/*       — contain OTHER projects' snapshot versions (sample data)
#
set -euo pipefail

OLD="${1:-}"
NEW="${2:-}"

if [[ -z "${OLD}" || -z "${NEW}" ]]; then
  echo "usage: $(basename "$0") OLD_VERSION NEW_VERSION" >&2
  echo "  e.g. $(basename "$0") 0.1.0-SNAPSHOT 0.1.0" >&2
  exit 1
fi
if [[ "${OLD}" == "${NEW}" ]]; then
  echo "error: OLD_VERSION and NEW_VERSION are identical (${OLD})." >&2
  exit 1
fi

# Release-form of each version (any trailing -SNAPSHOT stripped). Used for files
# that only ever carry the release version, such as the Claude plugin manifest.
OLD_REL="${OLD%-SNAPSHOT}"
NEW_REL="${NEW%-SNAPSHOT}"

# Resolve the repo root (this script lives in release/, one level down).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

# Apply one or more anchored perl substitutions to a "latest release" doc pointer
# file, setting each matched spot to $NEW_REL. Reports the count and records the
# file for the summary. Args: FILE, then one or more perl s/// statements that
# reference $ENV{NEW_REL}. Pass each statement single-quoted so the shell leaves
# $1/$ENV{...} for perl. Relies on `total` / `changed_files` being in scope.
bump_release_pointers() {
  local file="$1"; shift
  if [[ ! -f "${file}" ]]; then
    printf '  %-58s %s\n' "${file}" "skip (missing)"
    return
  fi
  local script="no warnings; " s
  for s in "$@"; do script+="\$c += ${s}; "; done
  script+='END { print STDERR $c+0 }'
  local n
  n=$(NEW_REL="${NEW_REL}" perl -i -pe "${script}" "${file}" 2>&1 >/dev/null || true)
  n="${n//[^0-9]/}"; n="${n:-0}"
  if [[ "${n}" -eq 0 ]]; then
    printf '  %-58s %s\n' "${file}" "no match"
  else
    printf '  %-58s updated (%s)\n' "${file}" "${n}"
    total=$((total + n))
    changed_files+=("${file}")
  fi
}

# Curated list of non-POM files that reference the Hierograph version.
# Add new ones here as they appear; keep RELEASING.md / docs/todos OUT.
FILES=(
  ".jqassistant.yml"
  "hierograph-itest/io.hierograph.itest.image/Dockerfile"
  "hierograph-itest/io.hierograph.itest.image/README.md"
  "BUILD.md"
  "docs/getting-started.md"
  "docs/specifications/jqassistant/rule-plugin-packaging.md"
)

echo "Replacing '${OLD}' -> '${NEW}' in non-POM files:"

total=0
changed_files=()
for f in "${FILES[@]}"; do
  if [[ ! -f "${f}" ]]; then
    printf '  %-58s %s\n' "${f}" "skip (missing)"
    continue
  fi
  # Count literal (fixed-string) occurrences before replacing.
  # `|| true` keeps a no-match grep (exit 1) from tripping `set -o pipefail`.
  n=$(grep -oF -- "${OLD}" "${f}" 2>/dev/null | wc -l | tr -d ' ' || true)
  if [[ "${n}" -eq 0 ]]; then
    printf '  %-58s %s\n' "${f}" "no match"
    continue
  fi
  # Literal, anchored replace via perl \Q..\E (no regex metachar surprises).
  OLD="${OLD}" NEW="${NEW}" perl -i -pe 's/\Q$ENV{OLD}\E/$ENV{NEW}/g' "${f}"
  printf '  %-58s updated (%s)\n' "${f}" "${n}"
  total=$((total + n))
  changed_files+=("${f}")
done

# The Claude plugin manifest carries the RELEASE-form version only
# ("version": "X.Y.Z", never -SNAPSHOT). Bump OLD_REL -> NEW_REL, and target only
# the top-level "version" field so unrelated X.Y.Z strings are never touched.
PLUGIN_JSON="plugins/hierograph/.claude-plugin/plugin.json"
if [[ ! -f "${PLUGIN_JSON}" ]]; then
  printf '  %-58s %s\n' "${PLUGIN_JSON}" "skip (missing)"
elif [[ "${OLD_REL}" == "${NEW_REL}" ]]; then
  # e.g. the release step 0.2.0-SNAPSHOT -> 0.2.0 leaves the release form as-is.
  printf '  %-58s %s\n' "${PLUGIN_JSON}" "no change (release version ${NEW_REL})"
else
  # `\Q..\E` isn't available in a bare grep, so escape dots for the count regex.
  n=$(grep -oE "\"version\"[[:space:]]*:[[:space:]]*\"${OLD_REL//./\\.}\"" "${PLUGIN_JSON}" 2>/dev/null | wc -l | tr -d ' ' || true)
  if [[ "${n}" -eq 0 ]]; then
    printf '  %-58s %s\n' "${PLUGIN_JSON}" "no match (version != ${OLD_REL})"
  else
    OLD_REL="${OLD_REL}" NEW_REL="${NEW_REL}" perl -i -pe 's/("version"\s*:\s*")\Q$ENV{OLD_REL}\E(")/$1$ENV{NEW_REL}$2/g' "${PLUGIN_JSON}"
    printf '  %-58s updated (%s)\n' "${PLUGIN_JSON}" "${n}"
    total=$((total + n))
    changed_files+=("${PLUGIN_JSON}")
  fi
fi

# "Latest release" doc pointers. Their current value is the PREVIOUS release
# (not OLD/NEW here), so each is matched by a stable anchor and set to NEW_REL.
# Advance them ONLY when NEW is a release version — on the "back to -SNAPSHOT"
# step the latest published release is unchanged.
if [[ "${NEW}" == "${NEW_REL}" ]]; then
  echo
  echo "Advancing 'latest release' doc pointers -> '${NEW_REL}':"
  # NB: docs/getting-started.md is deliberately NOT here. Its MCP image tag runs
  # the image you just built locally (`mvn -Pdocker package`), so it tracks the
  # WORKING version and is bumped verbatim via the FILES list above — not as a
  # published-release pointer. Only the bootstrap skill pulls from Maven Central.
  bump_release_pointers "plugins/hierograph/skills/hierograph-bootstrap/SKILL.md" \
    's/(currently \x60)\d+\.\d+\.\d+(\x60)/$1$ENV{NEW_REL}$2/g' \
    's/(returns \x60)\d+\.\d+\.\d+(\x60)/$1$ENV{NEW_REL}$2/g' \
    's/(hierograph-mcp-server:)\d+\.\d+\.\d+(?!-)/$1$ENV{NEW_REL}/g'
  bump_release_pointers "plugins/hierograph/skills/hierograph-bootstrap/scan-maven.md" \
    's/(version:\s*)\d+\.\d+\.\d+(\s+#\s*HIEROGRAPH_VERSION)/$1$ENV{NEW_REL}$2/g' \
    's/(\x60)\d+\.\d+\.\d+(\x60 at time of writing)/$1$ENV{NEW_REL}$2/g'
else
  echo
  echo "Latest-release doc pointers: unchanged (NEW is a -SNAPSHOT)."
fi

echo
if [[ "${total}" -eq 0 ]]; then
  echo "No occurrences of '${OLD}' found — nothing changed."
  exit 0
fi

echo "Replaced ${total} occurrence(s) across ${#changed_files[@]} file(s)."
if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo
  echo "Review the diff:"
  git --no-pager diff --stat -- "${changed_files[@]}"
fi
