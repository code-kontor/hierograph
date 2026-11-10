# Performing a release

**Part 3 of 3** — see the hub [`RELEASING.md`](RELEASING.md). Assumes a configured release machine
([`release-machine-setup.md`](release-machine-setup.md)) and the credentials from
[`credentials.md`](credentials.md).

This publishes the Java artifacts (libraries, Maven plugin, MCP server jar) to **Maven Central** and the
MCP server image to **GHCR**. The two are independent — you can do one without the other — but a normal
release does both at the same version.

> **`-o` (offline) does not apply to releasing.** Deploying to Central and pushing to GHCR both need
> network; the commands below intentionally omit `-o` (the sanity builds in Step 1 still use it).

In the commands below, `X.Y.Z` is the release version and `X.Y.Z+1` the next patch.

---

## Pre-flight

- **Release from `main`.** If the work lives on a feature branch, merge it into `main` first and release
  from there, so the tag lands on `main`:
  ```bash
  git checkout main && git merge --ff-only <feature-branch>   # or a normal merge if not fast-forwardable
  ```
- **Clean working tree** (`git status` empty).
- Pick the release version `X.Y.Z` — Central rejects `-SNAPSHOT`.

---

## 1. Set the release version

```bash
mvn versions:set -DnewVersion=X.Y.Z -DprocessAllModules=true
mvn versions:commit
./release/bump-nonpom-version.sh X.Y.Z-SNAPSHOT X.Y.Z
```

`versions:set` rewrites the POMs; the script bumps the non-POM carriers (`BUILD.md`, and the Claude
plugin manifest `plugin.json`, which tracks the release-form version).

> **Clean stray backups.** `versions:commit` leaves `*.versionsBackup` files under `hierograph-itest`,
> because that module is behind the `-Pitest` profile and isn't in the default reactor it operated on.
> Remove them so they don't sneak into the release commit:
> ```bash
> find . -name '*.versionsBackup' -delete
> ```

Sanity-check before publishing anything — **both must be green:**

```bash
mvn -o clean install        # unit tests
mvn clean install -Pitest   # integration tests (downloads the Spring fixture jars; needs network)
```

Leave the version bump **uncommitted** for now — it's committed in Step 4.

---

## 2. Publish the libraries to Maven Central

```bash
mvn clean deploy -Prelease
```

The `release` profile (in `hierograph-parent/pom.xml`) attaches `-sources` and Dokka `-javadoc` jars,
GPG-signs every artifact, and uploads via `central-publishing-maven-plugin`.

- **Published:** `hierograph-parent` (POM), the aggregator POMs, and every module under
  `hierograph-hierarchicalgraph`, `hierograph-graphstore`, `hierograph-server`, and
  `hierograph-build-support` — including the Maven plugin, the `io.hierograph.manifest` library, and the
  MCP server `io.hierograph.server` with its runnable `io.hierograph.server-X.Y.Z-exec.jar`.
- **Skipped:** `hierograph-itest` (only in the reactor under `-Pitest`).

`autoPublish` is `false`, so the upload lands as a **staged deployment**. Review it at
<https://central.sonatype.com/publishing/deployments> and click **Publish**. Sync to Central
search/repo then takes ~10–30 minutes.

---

## 3. Build and push the Docker image to GHCR

```bash
mvn -pl hierograph-server/io.hierograph.server -Pdocker deploy -Ddocker.push.skip=false
```

- Builds and pushes a **multi-arch** image (`linux/amd64,linux/arm64`) as one manifest list via
  `docker buildx` — multi-arch images can't be kept in the local store, so build and push are combined.
  Needs a `docker-container` buildx builder (created on demand as `hierograph-builder`) and, on Linux,
  QEMU/binfmt (present on Docker Desktop). Requires a prior `docker login ghcr.io`.
- Image: `ghcr.io/code-kontor/hierograph-mcp-server:X.Y.Z`. Override with `-Ddocker.registry=…` /
  `-Ddocker.image.shortname=…` / `-Ddocker.platforms=…`.
- Verify the registry got both arches:
  ```bash
  docker buildx imagetools inspect ghcr.io/code-kontor/hierograph-mcp-server:X.Y.Z
  ```
  (Two `unknown/unknown` entries alongside amd64/arm64 are buildx provenance/SBOM attestations — normal.)

> The `docker` profile sets `maven.deploy.skip=true`, because the image push binds to the `deploy`
> phase and that phase would otherwise also run `maven-deploy-plugin:deploy` — which has no repository
> for this module and fails before the push. (A plain `-Pdocker package`, with no push, does a local
> host-arch build for testing.)

---

## 4. Tag and bump to the next development version

```bash
git commit -am "Release X.Y.Z"
git tag vX.Y.Z
git push origin main
git push origin vX.Y.Z

mvn versions:set -DnewVersion=X.Y.Z+1-SNAPSHOT -DprocessAllModules=true
mvn versions:commit
find . -name '*.versionsBackup' -delete
./release/bump-nonpom-version.sh X.Y.Z X.Y.Z+1-SNAPSHOT
git commit -am "Start X.Y.Z+1-SNAPSHOT"
git push origin main
```

---

## Checklist

- [ ] Release machine ready (see [`release-machine-setup.md`](release-machine-setup.md) "Verify")
- [ ] On `main`, working tree clean, version chosen
- [ ] Version set to `X.Y.Z` across all modules (POMs **and** `bump-nonpom-version.sh`); stray
      `*.versionsBackup` removed
- [ ] `mvn -o clean install` and `mvn clean install -Pitest` both green
- [ ] `mvn clean deploy -Prelease` succeeded; staged deployment **Published** in the portal
- [ ] `-Pdocker deploy -Ddocker.push.skip=false` pushed the multi-arch image (verified with imagetools)
- [ ] `Release X.Y.Z` committed, `vX.Y.Z` tag pushed, bumped back to the next `-SNAPSHOT`

---

## Troubleshooting (hard-won)

- **`gpg: signing failed: No secret key`** (Step 2) — the signing key isn't in this machine's gpg
  keyring. Import it ([`release-machine-setup.md`](release-machine-setup.md) §2); the passphrase in
  `settings.xml` is **not** enough on its own.
- **Step 3: `repository element was not specified … distributionManagement`** — you're on an older
  checkout whose `docker` profile predates the `maven.deploy.skip=true` fix. Add
  `-Dmaven.deploy.skip=true` to the Step 3 command.
- **Image built but not pushed** (log shows `naming to … done` but no `pushing manifest`) — the local
  `docker build` ran, but the buildx `--push` step didn't, usually because the `deploy` phase aborted
  earlier (see the two items above). Re-run once the cause is fixed.
- **Stray `*.versionsBackup` files** after `versions:commit` — the `hierograph-itest` module is behind
  `-Pitest`; `find . -name '*.versionsBackup' -delete`.
