# Releasing Hierograph

This document describes how to cut a Hierograph release: the Java libraries to **Maven Central**
and the MCP server image to **GitHub Container Registry (GHCR)**. For day-to-day building see
[`BUILD.md`](../BUILD.md).

The two artifacts are independent — you can publish one without the other — but a normal release
does both at the same version.

> **`-o` (offline) does not apply to releasing.** The project's usual `mvn -o` habit is for local
> builds. Deploying to Central and pushing to GHCR both need network access; the commands below
> intentionally omit `-o`.

## One-time setup

These only need doing once (per machine / per account), not on every release.

### Maven Central (Central Publisher Portal)

1. **Claim the `io.hierograph` namespace** at <https://central.sonatype.com>. Verify domain
   ownership of `hierograph.io` by adding the TXT record the portal gives you.
2. **Generate a user token** in the portal (Account → Generate User Token).
3. **Add it to `~/.m2/settings.xml`** under the server id the POM expects (`central`):

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN-USERNAME</username>
         <password>TOKEN-PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```
4. **Publish a GPG key.** Central requires every artifact to be signed.

   ```bash
   gpg --gen-key                                   # if you don't have one
   gpg --list-secret-keys --keyid-format LONG      # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```

   The `maven-gpg-plugin` picks up the key via gpg-agent, or pass `-Dgpg.passphrase=...`.

> **Shortcut: generate `~/.m2/settings.xml` from Proton Pass.** Instead of hand-editing step 3
> (and the GPG passphrase), keep the token and passphrase in Proton Pass and let
> [`setup-m2-settings.sh`](setup-m2-settings.sh) render the file:
>
> ```bash
> cp release/settings.xml.template release/settings.xml.template.local   # one-time
> # edit the .local copy: VAULT/CENTRAL_ITEM/GPG_ITEM -> your real Proton Pass ids
> ./release/setup-m2-settings.sh                                          # needs an unlocked pass-cli
> ```
>
> The `.local` copy holds your real vault ids and is git-ignored; the committed
> [`settings.xml.template`](settings.xml.template) carries only generic placeholders. The script
> backs up any existing `~/.m2/settings.xml`, validates the result, and writes it mode 600.

### GHCR (Docker image)

Pushing the image needs a GitHub **Personal Access Token (classic)** with the `write:packages`
scope. Classic tokens remain the most reliable for GHCR.

1. Go to **GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)**
   (<https://github.com/settings/tokens>).
2. **Generate new token → Generate new token (classic)**; give it a name (e.g. `ghcr-push`) and an
   expiration.
3. Select scopes:
   - **`write:packages`** — push images (implies `read:packages`).
   - **`delete:packages`** — only if you also want to delete published images.
   - **`repo`** — only if the package is linked to a private repo.
4. **Generate token** and copy it immediately — it's shown only once.

> A fine-grained token with **Packages: Read and write** also works; classic tokens are just the
> most broadly compatible.

Then log in (the commands below read it from `$GHCR_PAT`):

```bash
export GHCR_PAT=<your-token>
echo "$GHCR_PAT" | docker login ghcr.io -u <github-user> --password-stdin
```

The login persists in `~/.docker/config.json`, so you normally only do it once per machine.
After that the release build (step 3) pushes the image for you. To push a tag by hand:

```bash
docker tag <local-image> ghcr.io/code-kontor/hierograph-mcp-server:<version>
docker push ghcr.io/code-kontor/hierograph-mcp-server:<version>
```

Log out again with `docker logout ghcr.io` if you want to clear the stored credentials.

## Release steps

### 1. Set the release version

Central rejects `-SNAPSHOT`. Bump the whole reactor in one shot:

```bash
mvn versions:set -DnewVersion=0.1.0 -DprocessAllModules=true
mvn versions:commit          # once happy; removes the *.versionsBackup files
```

`versions:set` only rewrites POMs. A handful of non-POM files (`.jqassistant.yml`, the itest
image `Dockerfile`/`README.md`, and the docs) also carry the version — bump those with the helper
script:

```bash
./release/bump-nonpom-version.sh 0.1.0-SNAPSHOT 0.1.0
```

It reports what it changed and prints a `git diff --stat`; review before committing. (It
deliberately skips `RELEASING.md` and `docs/todos/*`, whose version strings are examples / sample
data — see the script header.)

Sanity-check the build before publishing anything:

```bash
mvn -o clean install         # unit tests; everything green
```

```bash
mvn clean install -Pitest   # also run itests; everything green
```

### 2. Publish the libraries to Maven Central

The `release` profile (in `hierograph-parent/pom.xml`) attaches `-sources` and `-javadoc` (Dokka)
jars, GPG-signs every artifact, and uploads via the `central-publishing-maven-plugin`.

```bash
mvn clean deploy -Prelease
```

What deploys, and what doesn't:

- **Published:** `hierograph-parent` (POM) and the library modules under `hierograph-core`,
  `hierograph-hierarchicalgraph`, `hierograph-mcp` (the `javaspec`/`jqassistant`/`graphql` libs),
  and `hierograph-jqassistant`.
- **Skipped:** `io.hierograph.mcp.server` (an application, shipped as a Docker image — it sets
  `maven.deploy.skip=true`), and the `hierograph-itest` module (only in the reactor under `-Pitest`).

`autoPublish` is `false`, so the upload lands as a **staged deployment**. Review it at
<https://central.sonatype.com> (Deployments) and click **Publish**. Sync to Central search/repo
typically takes 10–30 minutes. To skip the manual gate, set `<autoPublish>true</autoPublish>` in the
profile.

### 3. Build and push the Docker image to GHCR

```bash
mvn -pl hierograph-mcp/io.hierograph.mcp.server -Pdocker deploy -Ddocker.push.skip=false
```

This builds `ghcr.io/code-kontor/hierograph-mcp-server:<version>` and pushes it. Override
`-Ddocker.registry=...` / `-Ddocker.image.shortname=...` to retarget.

> **Multi-arch.** The push builds a multi-arch image (`linux/amd64,linux/arm64`) with
> `docker buildx` and pushes the manifest list in one step — override with
> `-Ddocker.platforms=...`. It needs a `docker-container` buildx builder (created on demand as
> `hierograph-builder`) and, on Linux, QEMU/binfmt registered once via the `tonistiigi/binfmt`
> image (already present on Docker Desktop). A plain `-Pdocker package` (no push) still does a
> local host-arch `docker build` for testing.

### 4. Tag and bump to the next development version

```bash
git commit -am "Release 0.1.0"
git tag v0.1.0
git push && git push --tags

mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules=true
mvn versions:commit
./release/bump-nonpom-version.sh 0.1.0 0.2.0-SNAPSHOT   # the non-POM files again
git commit -am "Start 0.2.0-SNAPSHOT"
git push
```

## Release checklist

- [ ] One-time setup done (Central namespace + token, GPG key published, `docker login ghcr.io`)
- [ ] Version set to a non-SNAPSHOT release version across all modules (POMs **and**
      `./release/bump-nonpom-version.sh`)
- [ ] `mvn -o clean install` is green
- [ ] `mvn clean deploy -Prelease` succeeds; staged deployment **Published** in the portal
- [ ] `-Pdocker deploy -Ddocker.push.skip=false` pushed the image to GHCR
- [ ] Git tag pushed; version bumped back to the next `-SNAPSHOT`
