# fixture-app

A deterministic example project for Hierograph's tests.

It is **constructed by design**: every dependency, cycle and type relation is
intentional and documented with known expected values. This makes it the source
of truth for test data, in particular the GraphQL fixtures the web UI's browser
tests run against (`hierograph-web/src/testing/fixtures/`).

Stability rule: the `org.hg.fixture.basic.*` tenant must **never be modified**
after its expected values are published in `EXPECTED_VALUES.md`. Extensions go
into separate top-level tenants (e.g. `org.hg.fixture.advanced.*`), so existing
recordings stay unchanged.

The project is not part of the Hierograph build.

## Contents

- `src/main/java/` — the code.
- `.hierograph-views/` — hierarchy views for the tests: `layers` (valid) and
  `broken` (an invalid document, deliberately).
- [EXPECTED_VALUES.md](EXPECTED_VALUES.md) — dependency counts per package pair,
  cycle members, type inventory and DSM reference matrices.
- [LOCATIONS_SCENARIOS.md](LOCATIONS_SCENARIOS.md) — scenarios for a dependency
  locations view.

## Serving it

Prerequisites: Java 21, Maven 3.9+, and the Hierograph build installed in the
local Maven repository (`mvn install -DskipTests` at the repository root).

1. **Compile and write the build manifest** (`.hierograph/manifest.json`,
   git-ignored):

   ```bash
   cd examples/fixture-app
   mvn compile io.hierograph:hierograph-maven-plugin:0.4.0-SNAPSHOT:manifest
   ```

2. **Start the server** from this directory; it serves the project on port 8765:

   ```bash
   java -jar ../../hierograph-server/io.hierograph.server/target/io.hierograph.server-0.4.0-SNAPSHOT-exec.jar
   ```

   GraphQL: `http://localhost:8765/graphql`, web UI: `http://localhost:8765/ui/`.

Recording the web UI's fixtures from it is described in
`hierograph-web/src/testing/fixtures/README.md`.
