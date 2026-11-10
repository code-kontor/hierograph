# Building Hierograph

This document describes how to build the Hierograph project from source.

## Prerequisites

- **Java 21+** — the project targets Java 21 (`java.version` in `hierograph-parent/pom.xml`).
- **Maven 3.9+** — a recent Maven 3.9.x is recommended.
- **Kotlin** — no separate install needed; the Kotlin compiler (2.1.21) is driven by the
  `kotlin-maven-plugin` as part of the Maven build.
Verify your toolchain:

```bash
java -version    # should report 21 or newer
mvn -version
```

## Module layout

Hierograph is a multi-module Maven reactor. The root `pom.xml` is an aggregator; the actual
configuration lives in `hierograph-parent`.

| Module group                   | Contents                                                          |
| ------------------------------ | ----------------------------------------------------------------- |
| `hierograph-parent`            | Parent POM: dependency management, plugin config, Java/Kotlin versions |
| `hierograph-graphstore`        | The GraphStore and its Parquet snapshot format (`io.hierograph.graphstore.jvm`), and the bytecode scanner (`io.hierograph.graphstore.jvm.scanner`) |
| `hierograph-hierarchicalgraph` | The in-memory hierarchical graph model and its algorithms         |
| `hierograph-server`            | MCP server (`io.hierograph.server`), GraphQL (`io.hierograph.server.graphql`), the graph service they share (`io.hierograph.server.graphservice`: loading/reload, search, node properties, pagination), and the GraphStore-to-model mapping (`io.hierograph.server.mapping`) |
| `hierograph-maven-plugin`      | Maven plugin that records build facts and scans modules into `.hierograph` archives |
| `hierograph-itest`             | Integration tests (opt-in, see below)                            |

The runnable artifact is the Spring Boot MCP server in
`hierograph-server/io.hierograph.server` (main class `io.hierograph.mcp.server.McpApplicationKt`).

## Standard build

Build and install all default modules into your local Maven repository (`~/.m2`):

```bash
mvn -o install -DskipTests
```

Notes:

- **`-o` (offline) is recommended in this project.** The configured remote repositories return
  `401` and only slow the build down; everything needed should already be in your local `~/.m2`.
  Drop `-o` only the first time, if you still need to populate the local repository.
- **`install` (not just `package`)** places the Hierograph artifacts — including the
  `hierograph-maven-plugin` — into `~/.m2`, where Maven builds and downstream modules can
  resolve them. After changing a module's dependencies, run
  `mvn -o install -DskipTests` on the changed module before testing anything downstream;
  stale jars in `~/.m2` cause spurious "Unresolved reference" errors.

### Run the tests

```bash
mvn -o install        # runs unit tests as part of the build
# or, just the tests for the modules already built:
mvn -o test
```

A license header check (`license-maven-plugin`) runs in the `validate` phase. If you add new
`.kt`/`.java` files without the Apache 2.0 header, the build fails — run
`mvn -o license:format` to apply headers.

## Optional profiles

The integration tests are deliberately kept out of the default build and enabled via a profile.

### Integration tests — `-Pitest`

The integration tests live in `hierograph-itest` and are **not** part of the default reactor.
They run against a Parquet snapshot fixture (Spring jars scanned once per JVM) and need no Docker.

```bash
mvn -o -Pitest verify
```

## Running the MCP server

After a successful build, the runnable server jar is at:

```
hierograph-server/io.hierograph.server/target/io.hierograph.server-0.2.0-SNAPSHOT-exec.jar
```

Run it with:

```bash
java -jar hierograph-server/io.hierograph.server/target/io.hierograph.server-0.2.0-SNAPSHOT-exec.jar
```

> The repackaged (self-contained) jar carries the `exec` classifier so the plain
> `io.hierograph.server-0.2.0-SNAPSHOT.jar` remains the module's main artifact —
> downstream modules (e.g. the integration tests) compile against the server's classes,
> which a Spring Boot fat jar hides under `BOOT-INF/classes`.

or, during development, via the Spring Boot plugin:

```bash
mvn -o -pl hierograph-server/io.hierograph.server spring-boot:run
```

For end-to-end setup (preparing a target project for a watching server), see the
[usage guide of the Hierograph Maven plugin](hierograph-build-support/hierograph-maven-plugin/spec/hierograph-maven-plugin.md#7-usage).

> The `spring-boot:repackage` goal runs as part of the server module's `package` phase and attaches
> the self-contained, runnable Spring Boot jar under the `exec` classifier (the `-exec.jar` above).

## Building the Docker image

The build can also emit a Docker image of the MCP server. This is **opt-in** (it requires a running
Docker daemon) and uses the `docker` CLI against the `Dockerfile` beside the server module — it
stages the repackaged `-exec.jar` into a build context under `target/docker` and runs `docker build`.
(Spring Boot's buildpack-based `build-image` goal is deliberately avoided: its bundled platform pins
Docker API v1.24, which Docker Engine 29+ rejects.)

```bash
# Docker daemon must be running first (e.g. start Docker Desktop / colima).
mvn -o -pl hierograph-server/io.hierograph.server -Pdocker package -DskipTests
```

This produces the image:

```
ghcr.io/code-kontor/hierograph-mcp-server:0.2.0-SNAPSHOT
```

The image is built from the `Dockerfile` next to the server module (`eclipse-temurin:21-jre` over the
repackaged `-exec.jar`) via the `docker` CLI. The registry and name are properties — override
`-Ddocker.registry=...` or `-Ddocker.image.shortname=...` to retarget without editing the POM.

### Run the image

The image is self-contained: mount the target project's build root and your Maven directory
**read-only**, and everything else happens inside the container — producing the build manifest with
the baked-in Maven and Hierograph plugin (`hierograph:manifest`, no compilation), regenerating it after a
POM edit, scanning, and serving MCP on port `8080`:

```bash
docker run --rm -p 8080:8080 \
  -v /path/to/project:/workspace:ro \
  -v ~/.m2:/m2:ro \
  [-v hierograph-state:/hierograph] \
  [-e HIEROGRAPH_MAVEN_ARGS=-Pitest] \
  ghcr.io/code-kontor/hierograph-mcp-server:0.2.0-SNAPSHOT
```

- `/m2/repository` serves as a read-only tail of the container's own local repository; only missing
  artifacts are downloaded into the state directory. `/m2/settings.xml` is used when present.
- `/hierograph` holds all state — manifest, Parquet snapshot, regeneration log, downloaded
  dependencies. Mount a named volume there to keep it across restarts; without it, each start
  regenerates the manifest first.
- `HIEROGRAPH_MAVEN_ARGS` passes extra Maven arguments (e.g. profiles that add modules);
  `HIEROGRAPH_BUILD_FILES` sets the reaction to edited POMs (`regenerate` by default).
- The host keeps compiling as usual; the container picks the classes up through the mount (polling).

### Push the image

Pushing is **opt-in** — a plain `-Pdocker package` only builds locally. The push is bound to the
`deploy` phase and gated by `docker.push.skip` (default `true`). Authenticate first, then enable it:

```bash
# One-time: log in to GitHub Container Registry with a PAT that has `write:packages`.
echo "$GHCR_PAT" | docker login ghcr.io -u <github-user> --password-stdin

mvn -o -pl hierograph-server/io.hierograph.server -Pdocker deploy -Ddocker.push.skip=false
```

> Single-arch: this builds for your host architecture. For multi-arch (amd64 + arm64) pulls you'd
> need a `docker buildx --platform ... --push` build instead.

## Common build commands

| Goal                                    | Command                                                  |
| --------------------------------------- | -------------------------------------------------------- |
| Full build + install, skip tests        | `mvn -o install -DskipTests`                             |
| Full build with unit tests              | `mvn -o install`                                         |
| Rebuild a single module                 | `mvn -o -pl <module-path> install`                       |
| Rebuild a module **and its dependents** | `mvn -o -pl <module-path> -amd install`                  |
| Integration tests (needs Docker)        | `mvn -o -Pitest verify`                                  |
| Build MCP server Docker image (needs Docker) | `mvn -o -pl hierograph-server/io.hierograph.server -Pdocker package` |
| Build **and push** the image (needs `docker login`) | `mvn -o -pl hierograph-server/io.hierograph.server -Pdocker deploy -Ddocker.push.skip=false` |
| Apply license headers                   | `mvn -o license:format`                                  |
| Clean                                   | `mvn -o clean`                                           |
