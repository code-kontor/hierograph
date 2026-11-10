# Releasing Hierograph

How to cut a Hierograph release: the Java artifacts — libraries, the Maven plugin and the MCP server
jar — to **Maven Central**, and the MCP server image to **GitHub Container Registry (GHCR)**.

The process is split into three guides, in order:

1. **[credentials.md](credentials.md)** — create the required accounts, tokens and GPG key material, and
   store them in your password manager. *Once per person / account.*
2. **[release-machine-setup.md](release-machine-setup.md)** — turn a local machine into a release
   machine from those credentials. *Once per machine.*
3. **[performing-a-release.md](performing-a-release.md)** — cut an actual release. *Every time.*

For day-to-day building (not releasing), see [`BUILD.md`](../../BUILD.md).

> The two artifacts are **independent** — you can publish the libraries without the image, or vice
> versa — but a normal release does both at the same version.

> **`-o` (offline) does not apply to releasing.** The project's usual `mvn -o` habit is for local
> builds; deploying to Central and pushing to GHCR both need network access.

## Helper files (in `release/`)

- [`bump-nonpom-version.sh`](../bump-nonpom-version.sh) — bumps the version in the non-POM files Maven's
  `versions:set` doesn't touch (used in [performing-a-release.md](performing-a-release.md)).
- [`settings.xml.template`](../settings.xml.template) + [`setup-m2-settings.sh`](../setup-m2-settings.sh) —
  render `~/.m2/settings.xml` from Proton Pass (optional; see
  [release-machine-setup.md](release-machine-setup.md)).
