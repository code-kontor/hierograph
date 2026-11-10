# Setting up a release machine

**Part 2 of 3** — see the hub [`RELEASING.md`](RELEASING.md). Previous: [`credentials.md`](credentials.md).
Next: [`performing-a-release.md`](performing-a-release.md).

Turn a local machine into one that can cut a release, using the credentials from
[`credentials.md`](credentials.md). **Once per machine.** For day-to-day building see
[`BUILD.md`](../../BUILD.md).

---

## Prerequisites (tooling)

- **Java 21+** and **Maven 3.9+** — see [`BUILD.md`](../../BUILD.md).
- **git**.
- **GnuPG** (`gpg`).
- **Docker Desktop** — only for the GHCR image step (Step 3). It must be **running**, and it provides
  the `buildx` + QEMU/binfmt needed for the multi-arch build.

---

## 1. Maven settings — `~/.m2/settings.xml`

A release needs two things in `~/.m2/settings.xml`:
- a **`<server id="central">`** carrying your Maven Central token, and
- a **`gpg-sign` profile** carrying the GPG **passphrase** (so `maven-gpg-plugin` can sign unattended).

Pick one route.

### Route A — Proton Pass automation (what the repo scripts support)

The committed [`settings.xml.template`](../settings.xml.template) holds `{{ pass://VAULT/ITEM/FIELD }}`
references that [`setup-m2-settings.sh`](../setup-m2-settings.sh) resolves with the Proton Pass CLI.

1. Install the **Proton Pass CLI** (`pass-cli`) and authenticate/unlock a session (see the tool's own
   documentation — it must be on your `PATH` and logged in).
2. Make a local, git-ignored copy and fill in your **real** vault/item ids:
   ```bash
   cp release/settings.xml.template release/settings.xml.template.local
   # edit the .local copy: replace VAULT / CENTRAL_ITEM / GPG_ITEM with your ids.
   # discover them with:  pass-cli vault list   and   pass-cli item list <vault>
   ```
   A reference is `pass://<vault>/<item>/<field>` (fields e.g. `Username`, `Password`, `Passphrase`).
3. Render the file:
   ```bash
   ./release/setup-m2-settings.sh
   ```
   It resolves the refs via `pass-cli inject`, validates the XML, backs up any existing
   `~/.m2/settings.xml`, and writes the result mode `600`.

### Route B — manual (any password manager)

Retrieve the Central token and the GPG passphrase from your manager and write `~/.m2/settings.xml`
by hand:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN-USERNAME</username>
      <password>TOKEN-PASSWORD</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>gpg-sign</id>
      <properties>
        <gpg.passphrase>YOUR-GPG-PASSPHRASE</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>gpg-sign</activeProfile>
  </activeProfiles>
</settings>
```

> Instead of storing the passphrase in `settings.xml`, you can let `gpg-agent` prompt for it, or pass
> `-Dgpg.passphrase=…` on the Step 2 command — but that lands in shell history, so the profile is
> preferred.

---

## 2. Import the GPG secret key into the keyring

**This is separate from the passphrase, and it's the step most easily forgotten.** `maven-gpg-plugin`
signs with the secret key in the machine's gpg keyring; the passphrase in `settings.xml` can't sign on
its own.

```bash
# retrieve the armored secret key from your password manager to a file, then:
gpg --import hierograph-signing.asc
gpg --list-secret-keys --keyid-format LONG     # MUST show a `sec` line for your key
rm -P hierograph-signing.asc                    # wipe the on-disk copy
```

Also confirm the matching **public** key is on a keyserver (done in [`credentials.md`](credentials.md) §2);
Central checks it when validating the deployment.

> If `gpg --list-secret-keys` shows nothing, signing in Step 2 will fail with
> `gpg: signing failed: No secret key`. Don't proceed until a `sec` line appears.

---

## 3. Log in to GHCR (only if you'll publish the image)

Use the classic PAT from [`credentials.md`](credentials.md) §3, piped via stdin so it never lands in
shell history:

```bash
read -rsp "GHCR PAT: " GHCR_PAT && echo "$GHCR_PAT" | docker login ghcr.io -u YOUR_GITHUB_USERNAME --password-stdin; unset GHCR_PAT
```

`Login Succeeded` persists in `~/.docker/config.json` — one-time per machine.

---

## Verify the machine is ready

```bash
gpg --list-secret-keys --keyid-format LONG                 # your key (a `sec` line)
grep -q '<id>central</id>'  ~/.m2/settings.xml && echo "central server: ok"
grep -q 'gpg.passphrase'    ~/.m2/settings.xml && echo "gpg passphrase: ok"
grep -q 'ghcr.io'           ~/.docker/config.json && echo "ghcr login: ok"   # image only
mvn -version                                               # Java 21
```

All green → you're ready for [`performing-a-release.md`](performing-a-release.md).
