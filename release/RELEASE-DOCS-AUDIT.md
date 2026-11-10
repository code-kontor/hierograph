# Release docs audit — 2026-11-10

> **Status:** findings 1–5 applied in the same commit as this document; the line numbers below refer to
> the pre-fix revision. Kept as the record of what was corrected.

Audit of the `release/` directory (`RELEASING.md`, `bump-nonpom-version.sh`, `settings.xml.template`,
`setup-m2-settings.sh`) against the current repository, after the jQAssistant/Neo4j/bolt retirement and
the module renames. Every path, module coordinate, and profile referenced by the release docs was
cross-checked against the actual tree.

**Verdict:** the publish path (§2) and the Maven Central / GPG setup are accurate, but the Docker release
command (§3) was **broken** (wrong module path), and both the itest-image references and the
`bump-nonpom-version.sh` "latest-release pointers" targeted files/modules that no longer exist.

---

## Up to date ✅

- **Maven Central one-time setup**, `settings.xml.template`, `setup-m2-settings.sh` — credential/auth
  only (server id `central`, the `gpg-sign` profile, the Proton Pass `pass://` helper). No architecture
  coupling; all accurate.
- **§2 Publish — `mvn clean deploy -Prelease`.** The `release` profile exists in
  `hierograph-parent/pom.xml` (line ~353) with `dokka-maven-plugin`, `maven-gpg-plugin`, and
  `central-publishing-maven-plugin`, exactly as described. The Published/Skipped module list is correct:
  published = `hierograph-hierarchicalgraph`, `hierograph-graphstore`, `hierograph-server`,
  `hierograph-build-support` (incl. the Maven plugin and the `io.hierograph.manifest` library) + the
  server `-exec.jar`; skipped = `hierograph-itest`.
- **§3 image coordinates** — `ghcr.io/code-kontor/hierograph-mcp-server:<version>` matches the server
  pom (`docker.registry=ghcr.io/code-kontor`, `docker.image.shortname=hierograph-mcp-server`). The
  multi-arch / buildx notes are accurate.
- **`plugin.json` version bump** — `plugins/hierograph/.claude-plugin/plugin.json` is present and holds
  the release-form version (`"version": "0.2.0"`); the script's release-form bump path works.

---

## Stale / broken ❌ (all fixed)

### 1. RELEASING.md §3 — the documented Docker command fails
**Lines 155 & 158.** The command used `-pl hierograph-mcp/io.hierograph.mcp.server`, a module that no
longer exists. The `-Pdocker` profile now lives in **`hierograph-server/io.hierograph.server`**.

- Was (broken):
  ```bash
  mvn -pl hierograph-mcp/io.hierograph.mcp.server -Pdocker deploy -Ddocker.push.skip=false
  ```
- Now:
  ```bash
  mvn -pl hierograph-server/io.hierograph.server -Pdocker deploy -Ddocker.push.skip=false
  ```

Running the old command failed with "Could not find the selected project". **Was the highest priority** —
it broke a release step.

### 2. RELEASING.md §1 — references a deleted module
**Lines 107–109.** The note said a handful of non-POM files "(the itest image `Dockerfile`/`README.md`
and `BUILD.md`)" carry the version. The `hierograph-itest/io.hierograph.itest.image` module is **gone** —
`hierograph-itest/` now contains only `io.hierograph.itest.tests`, which runs off a Parquet fixture
(no Docker image). The only non-POM version carriers now are `BUILD.md` and `plugin.json`. The prose was
updated to drop the itest-image mention.

### 3. bump-nonpom-version.sh `FILES` — two dead entries
**Lines 83–84.** `FILES` listed `hierograph-itest/io.hierograph.itest.image/Dockerfile` and
`.../README.md`, both missing (module removed). The script tolerated this (`skip (missing)`), but they
were dead entries. Removed; `FILES` is now just `BUILD.md`.

### 4. bump-nonpom-version.sh "latest-release pointers" — now a no-op
**Lines 140–146.** This block advanced version pointers in the bootstrap skill on a release. Both targets
were stale:

- `plugins/hierograph/skills/hierograph-bootstrap/scan-maven.md` — **does not exist**. The bootstrap
  skill is now a single `SKILL.md`.
- The `SKILL.md` anchors it greps for — `currently \`X.Y.Z\``, `returns \`X.Y.Z\``,
  `hierograph-mcp-server:X.Y.Z` — **match nothing** in the current skill. `SKILL.md` deliberately uses
  the `HIEROGRAPH_VERSION` placeholder (resolved from Maven Central at run time) plus a "**0.2.0 or
  later**" floor, i.e. it no longer embeds a literal version pointer to advance.

Because the skill intentionally holds no literal pointer, the fix **removed** this pointer block (and the
now-unused `bump_release_pointers` helper), rather than re-anchoring it. If a literal "latest release"
pointer is ever reintroduced into the skill, add a matching anchor back then.

---

## Minor / informational

- **Version examples predate the current line.** RELEASING.md and the script header used `0.1.0` /
  `0.1.0-SNAPSHOT` as worked examples while the repo is at `0.2.0-SNAPSHOT`. Refreshed to the live line
  (`0.2.0` release / `0.3.0-SNAPSHOT` next dev). These were always labelled as examples, so not wrong —
  just refreshed for clarity.
- **Other `0.2.0-SNAPSHOT` occurrences** outside the tracked version carriers:
  `plugins/hierograph/skills/hierograph-structure/references/layered.md` and
  `plugins/hierograph/skills/hierograph-structure/scripts/make_data.py`. These look like illustrative
  sample GAVs in example output, not release-tracked version strings — left out of the bump script.

---

## Remediation applied

| # | File | Change |
|---|------|--------|
| 1 | `RELEASING.md` §3 | `-pl hierograph-mcp/io.hierograph.mcp.server` → `-pl hierograph-server/io.hierograph.server` |
| 2 | `RELEASING.md` §1 | Dropped the itest-image mention; non-POM carriers are now `BUILD.md` + `plugin.json` |
| 3 | `bump-nonpom-version.sh` | Removed the two `io.hierograph.itest.image/*` entries from `FILES` |
| 4 | `bump-nonpom-version.sh` | Removed the `scan-maven.md` / `SKILL.md` "latest-release pointers" block + unused helper |
| 5 | `RELEASING.md`, script header | Refreshed `0.1.0` examples to the live `0.2.0` / `0.3.0-SNAPSHOT` line |
