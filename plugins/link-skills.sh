#!/usr/bin/env bash
# Symlink every skill in plugins/hierograph/skills/ into .claude/skills/ so this
# repo's own Claude Code session loads them live. Links are relative, so the
# checkout can be moved. Re-running is safe; stale links to removed skills are
# cleaned up. Real directories already in .claude/skills/ are never touched.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src_dir="$repo_root/plugins/hierograph/skills"
dst_dir="$repo_root/.claude/skills"
rel_prefix="../../plugins/hierograph/skills"

mkdir -p "$dst_dir"

# Remove dangling links that point into the plugin skills dir.
for link in "$dst_dir"/*; do
  [[ -L "$link" && ! -e "$link" ]] || continue
  [[ "$(readlink "$link")" == "$rel_prefix/"* ]] || continue
  rm "$link"
  echo "removed stale  $(basename "$link")"
done

for skill in "$src_dir"/*/; do
  name="$(basename "$skill")"
  target="$rel_prefix/$name"
  link="$dst_dir/$name"

  if [[ -L "$link" ]]; then
    if [[ "$(readlink "$link")" == "$target" ]]; then
      echo "ok             $name"
      continue
    fi
    rm "$link"
  elif [[ -e "$link" ]]; then
    echo "skipped        $name (non-symlink already exists at $link)" >&2
    continue
  fi

  ln -s "$target" "$link"
  echo "linked         $name"
done
