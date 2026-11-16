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
| `hierograph-graphstore`        | The scanned-fact model and the Parquet snapshot format and writer (`io.hierograph.graphstore.jvm.archive`), the GraphStore with the snapshot reader and detail queries (`io.hierograph.graphstore.jvm.store`), and the bytecode scanner (`io.hierograph.graphstore.jvm.scanner`) |
| `hierograph-hierarchicalgraph` | The in-memory hierarchical graph model and its algorithms         |
| `hierograph-server`            | MCP server (`io.hierograph.server`), GraphQL (`io.hierograph.server.graphql`), the graph service they share (`io.hierograph.server.graphservice`: loading/reload, search, node properties, pagination), and the GraphStore-to-model mapping (`io.hierograph.server.mapping`) |
| `hierograph-build-support`     | The build-tool-independent manifest library (`io.hierograph.manifest`) and the Maven plugin (`hierograph-maven-plugin`) that records build facts and scans modules into `.hierograph` archives. The Gradle plugin (`hierograph-gradle-plugin`) lives here too, as a Gradle build of its own (see below) |
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

The integration tests and the coverage reports are deliberately kept out of the default build
and enabled via profiles.

### Test coverage — `-Pcoverage`

The `coverage` profile runs each module's tests with the JaCoCo agent and writes a report per
module in the `verify` phase:

```bash
mvn -o -Pcoverage clean verify
# or one module:
mvn -o -Pcoverage -pl hierograph-graphstore/io.hierograph.graphstore.jvm.archive clean verify
```

The report is at `<module>/target/site/jacoco/index.html`, with `jacoco.xml` and `jacoco.csv`
next to it. It measures a module's classes against **its own tests only**: a class that is
exercised only by another module's tests, or by the integration tests, counts as not covered.
Use `clean` so that coverage data from an earlier run is not merged in.

### Integration tests — `-Pitest`

The integration tests live in `hierograph-itest` and are **not** part of the default reactor.
They run against a Parquet snapshot fixture (Spring jars scanned once per JVM) and need no Docker.

```bash
mvn -o -Pitest verify
```

### The Gradle plugin — its own Gradle build

`hierograph-build-support/hierograph-gradle-plugin` is a Gradle build with its own wrapper, outside the
Maven reactor; the Maven build doesn't build it. It depends on the `io.hierograph.manifest` library, which
it takes from `~/.m2`, so install that first:

```bash
mvn -o install -pl hierograph-build-support/io.hierograph.manifest -am -DskipTests
cd hierograph-build-support/hierograph-gradle-plugin
./gradlew build                 # compiles, validates the plugin, runs the TestKit tests (Gradle 8.11 and current)
./gradlew publishToMavenLocal   # for the init script, and for the Gradle integration tests below
```

The TestKit tests download Gradle 8.11 and the fixtures' dependencies on first run, so they need network.

The integration tests that compare the Maven and Gradle manifests and run the server on a Gradle build are
opt-in. They need the Gradle plugin published to `~/.m2` and the reactor installed:

```bash
mvn -Pitest verify -pl hierograph-itest/io.hierograph.itest.tests -Dhierograph.itest.gradle=true -Dit.test='Gradle*IT'
```

## Running the MCP server

After a successful build, the runnable server jar is at:

```
hierograph-server/io.hierograph.server/target/io.hierograph.server-0.3.0-SNAPSHOT-exec.jar
```

Run it with:

```bash
java -jar hierograph-server/io.hierograph.server/target/io.hierograph.server-0.3.0-SNAPSHOT-exec.jar
```

The server listens on `127.0.0.1` only (`server.address` in `application.properties`): it serves the
code base without authentication, so other machines on the network can't reach it. To expose it
deliberately, pass `--server.address=0.0.0.0`.

Spring Boot Actuator serves operational endpoints under `/actuator` on the same port:

| Endpoint | Default | Content |
| -------- | ------- | ------- |
| `health`, `info` | on | status and build info |
| `metrics` | on | JVM memory, GC, threads, CPU, e.g. `/actuator/metrics/jvm.memory.used?tag=area:heap` |
| `env` | **off** | the configuration properties, all values masked |
| `threaddump` | **off** | the server's threads with their stacks |
| `heapdump` | **off** | a heap dump (`.hprof`) to download, as large as the used heap; it holds the scanned code base |

**The diagnostic endpoints `env`, `threaddump` and `heapdump` are off by default** and answer 404.
Enable them for a diagnosis with `hierograph.actuator.diagnostics.enabled=true` and restart the server:

```bash
# local JVM
java -jar hierograph-server/io.hierograph.server/target/io.hierograph.server-0.3.0-SNAPSHOT-exec.jar \
  --hierograph.actuator.diagnostics.enabled=true

# container: the environment is fixed at creation, so recreate it with the variable added
docker run ... -e HIEROGRAPH_ACTUATOR_DIAGNOSTICS_ENABLED=true ... ghcr.io/code-kontor/hierograph-mcp-server:0.3.0-SNAPSHOT
```

To keep them on across restarts of a local server, put `hierograph.actuator.diagnostics.enabled=true`
into the workspace's `.hierograph/server.properties`. Start the server without the switch to turn them
off again.

> The repackaged (self-contained) jar carries the `exec` classifier so the plain
> `io.hierograph.server-0.3.0-SNAPSHOT.jar` remains the module's main artifact —
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
ghcr.io/code-kontor/hierograph-mcp-server:0.3.0-SNAPSHOT
```

The image is built from the `Dockerfile` next to the server module (`eclipse-temurin:21-jre` over the
repackaged `-exec.jar`) via the `docker` CLI. The registry and name are properties — override
`-Ddocker.registry=...` or `-Ddocker.image.shortname=...` to retarget without editing the POM.

### Run the image

The image is self-contained: mount the target project's build root and your Maven directory
**read-only**, and everything else happens inside the container — producing the build manifest with
the baked-in Maven and Hierograph plugin (`hierograph:manifest`, no compilation), regenerating it after a
POM edit, scanning, and serving MCP on port `8765`:

```bash
docker run --rm -p 127.0.0.1:8765:8765 \
  -e HIEROGRAPH_URL=http://localhost:8765/mcp \
  -e HIEROGRAPH_WORKSPACE=/path/to/project \
  -v /path/to/project:/workspace:ro \
  -v ~/.m2:/m2:ro \
  [-v hierograph-state:/hierograph] \
  [-e HIEROGRAPH_MAVEN_ARGS=-Pitest] \
  ghcr.io/code-kontor/hierograph-mcp-server:0.3.0-SNAPSHOT
```

- `/m2/repository` serves as a read-only tail of the container's own local repository; only missing
  artifacts are downloaded into the state directory. `/m2/settings.xml` is used when present.
- `/hierograph` holds all state — manifest, Parquet snapshot, regeneration log, downloaded
  dependencies. Mount a named volume there to keep it across restarts; without it, each start
  regenerates the manifest first.
- `HIEROGRAPH_MAVEN_ARGS` passes extra Maven arguments (e.g. profiles that add modules);
  `HIEROGRAPH_BUILD_FILES` sets the reaction to edited POMs (`regenerate` by default).
- `HIEROGRAPH_JAVA_OPTS` holds the server's JVM options. The image sets
  `-Xmx2g -XX:+UseStringDeduplication` (G1 shares the contents of equal strings, about 2 % of the live
  heap). An override replaces the whole value, so keep the flag when raising the heap for a larger
  reactor: `-e "HIEROGRAPH_JAVA_OPTS=-Xmx4g -XX:+UseStringDeduplication"`.
- `HIEROGRAPH_URL` and `HIEROGRAPH_WORKSPACE` make `server_info` report the URL and the project path
  as the host sees them, instead of the container's own address and `/workspace`. With another host
  port (`-p 127.0.0.1:9000:8765`), set `HIEROGRAPH_URL` to that port.
- `127.0.0.1:` publishes the port on the host's loopback interface only: the server serves the code
  base without authentication, so other machines must not reach it. A plain `-p 8765:8765` listens on
  every interface of the host. Inside the container the server listens on all interfaces (the
  entrypoint sets `SERVER_ADDRESS=0.0.0.0`), which Docker's port forwarding needs.
- The host keeps compiling as usual; the container picks the classes up through the mount (polling).

A **Gradle** build is different: the host writes the manifest with the Hierograph Gradle plugin, and the
container only scans and serves it. A Gradle build definition is a program that needs the host's JDKs,
credentials and init scripts, so it doesn't run in the container (see §8.4 of the
[Gradle plugin's spec](hierograph-build-support/hierograph-gradle-plugin/spec/hierograph-gradle-plugin.md)):

```bash
./gradlew --init-script /path/to/hierograph.init.gradle hierographManifest   # on the host, writes .hierograph/
docker run --rm -p 127.0.0.1:8765:8765 \
  -e HIEROGRAPH_URL=http://localhost:8765/mcp \
  -e HIEROGRAPH_WORKSPACE=/path/to/project \
  -v /path/to/project:/workspace:ro \
  [-v hierograph-state:/hierograph] \
  ghcr.io/code-kontor/hierograph-mcp-server:0.3.0-SNAPSHOT
```

- The build tool is detected: `pom.xml` → Maven, a settings or build script → Gradle; a build root with
  both is Maven unless `HIEROGRAPH_BUILD_TOOL=gradle`.
- The server maps the manifest's host paths to `/workspace`. An edited build script makes the build
  state `stale`; rerunning `hierographManifest` on the host rewrites the manifest, and the server applies it.

The container test runs the image on a Gradle build (Docker running, the image built as above, the Gradle
plugin published with `./gradlew publishToMavenLocal`):

```bash
mvn -Pitest verify -pl hierograph-itest/io.hierograph.itest.tests -Dhierograph.itest.gradle=true -Dhierograph.itest.docker=true -Dit.test=GradleContainerIT
```

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
| Unit tests with coverage reports        | `mvn -o -Pcoverage clean verify`                         |
| Build MCP server Docker image (needs Docker) | `mvn -o -pl hierograph-server/io.hierograph.server -Pdocker package` |
| Build **and push** the image (needs `docker login`) | `mvn -o -pl hierograph-server/io.hierograph.server -Pdocker deploy -Ddocker.push.skip=false` |
| Apply license headers                   | `mvn -o license:format`                                  |
| Clean                                   | `mvn -o clean`                                           |
