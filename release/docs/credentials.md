# Release credentials & key material

**Part 1 of 3** — see the hub [`RELEASING.md`](RELEASING.md). Next: [`release-machine-setup.md`](release-machine-setup.md).

What you create **once per person / account** before Hierograph can be released, and what to stash in
your password manager so any machine can later be turned into a release machine.

There are three independent credentials. A normal release uses all three, but the two artifacts —
the **libraries** on Maven Central and the **MCP server image** on GHCR — are independent and can be
published separately, so you only need the credentials for the artifact(s) you publish.

> **Password manager of choice.** Store everything below in whatever manager you use. The repo ships
> optional **Proton Pass** automation that renders `~/.m2/settings.xml` for you (covered in
> [`release-machine-setup.md`](release-machine-setup.md)), but nothing here is Proton-specific — any
> manager works, as long as you can retrieve these values when setting up a machine.

---

## 1. Maven Central — publisher account + user token

1. Sign in at <https://central.sonatype.com> and **claim the `io.hierograph` namespace**. Verify
   ownership of the matching domain (`hierograph.io`) by adding the TXT record the portal shows you.
   (One-time for the namespace; already done if a previous release published under `io.hierograph`.)
2. **Generate a user token**: Account → *Generate User Token*. You get a token **username** and
   **password** (not your portal login).

**Store in your manager:** the token username and password. The POM expects them under the Maven
server id **`central`**.

---

## 2. GPG signing key (+ passphrase)

Maven Central requires every artifact to be GPG-signed, and the matching **public** key must be
discoverable on a public keyserver so Central can verify the signatures.

```bash
gpg --full-generate-key                              # create a key, set a passphrase
gpg --list-secret-keys --keyid-format LONG           # note the long KEY_ID

# make the PUBLIC key discoverable (Central verifies against it):
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
#   (keys.openpgp.org is a fine alternative / addition)

# export the SECRET key for safekeeping:
gpg --export-secret-keys --armor <KEY_ID> > hierograph-signing.asc
```

**Store in your manager:**
- the armored **secret** key file `hierograph-signing.asc` (as an attachment or a secure note), **and**
- the key **passphrase** (a dedicated field).

Then **delete the local file** — `rm -P hierograph-signing.asc` — so the private key isn't left on disk.

> **The gotcha that bites on release day:** you need *both* of these on a release machine, for two
> different places — the **secret key** gets imported into the machine's gpg keyring, the **passphrase**
> goes into Maven's `settings.xml`. Having only the passphrase is not enough; signing then fails with
> `gpg: signing failed: No secret key`. See [`release-machine-setup.md`](release-machine-setup.md) §2.

---

## 3. GitHub token for GHCR (the Docker image)

Pushing the image needs a GitHub **Personal Access Token (classic)**:

1. GitHub → **Settings → Developer settings → Personal access tokens → Tokens (classic)**
   (<https://github.com/settings/tokens>) → *Generate new token (classic)*.
2. Scope: **`write:packages`** (implies `read:packages`). Add `delete:packages` only if you want to
   delete images; `repo` only if the package is linked to a private repo.
3. Copy the token immediately — it's shown once.

Your GitHub account must have **write access to the `code-kontor` org's packages**, since the image
publishes to `ghcr.io/code-kontor/hierograph-mcp-server`.

> A fine-grained token with **Packages: Read and write** also works; classic tokens are just the most
> broadly compatible.

**Store in your manager:** the PAT (and your GitHub username — it's the `docker login` user).

---

## Summary — what ends up in your password manager

| Credential | Fields to store | Used for |
| --- | --- | --- |
| Maven Central user token | username, password | `settings.xml` server `central` (Step 2) |
| GPG signing key | armored **secret** key + passphrase | keyring import + `settings.xml` `gpg-sign` (Step 2) |
| GitHub PAT (classic) | token (+ GitHub username) | `docker login ghcr.io` (Step 3) |

Next: [`release-machine-setup.md`](release-machine-setup.md) — wiring these into a machine.
