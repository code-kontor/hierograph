#!/usr/bin/env bash
#
# bump-nonpom-version.sh OLD_VERSION NEW_VERSION
#
# Replaces the Hierograph project version in the NON-POM files that carry it.
# POM files are handled separately by `mvn versions:set` (see RELEASING.md);
# this script covers the references Maven doesn't know about.
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

# Resolve the repo root (this script lives in release/, one level down).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

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
