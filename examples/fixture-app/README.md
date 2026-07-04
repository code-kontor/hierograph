# fixture-app

A deterministic test-fixture application for hierograph.

Unlike plantify (which serves as a realistic but organically grown example),
this application is **constructed by design**: every dependency, cycle, and
type relation is intentional and documented with known expected values.
This makes it the canonical source of truth for test assertions in the
`frontend-testing` feature and future backend/API tests.

Stability rule: the `org.hg.fixture.basic.*` tenant must **never be modified**
after its expected values are published in `EXPECTED_VALUES.md`. Extensions
go into separate top-level tenants (e.g. `org.hg.fixture.advanced.*`).

## Expected values

See [EXPECTED_VALUES.md](EXPECTED_VALUES.md) for the canonical reference:
documented dependency counts per package pair, SCC members, type inventory,
and DSM reference matrices.

## Prerequisites

- Java 21
- Maven 3.9+
- hierograph rules plugin `0.2.0-SNAPSHOT` in local Maven repository:
  ```
  cd <repo-root>
  mvn install -DskipTests
  ```

## Scan loop

**Only one store at a time on `bolt://localhost:7687`** — stop any running plantify server first.

1. **Build and scan** (generates `fixture-db/`):
   ```
   cd hierograph/examples/fixture-app
   mvn clean install -Pjqassistant
   ```

2. **Serve the store** (blocks — run in a separate terminal):
   ```
   cd hierograph/examples/fixture-app
   mvn com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:server
   ```
   → Neo4j bolt: `bolt://localhost:7687`, HTTP browser: `http://localhost:7474`

3. **Start the MCP server** (in a separate terminal):
   ```
   cd hierograph/hierograph-mcp/io.hierograph.mcp.server
   mvn spring-boot:run
   ```
   → GraphQL: `http://localhost:8080/graphql`

The store is disposable and git-ignored — regenerate it from source at any time with step 1.
The source code is the versioned representation; `fixture-db/` is never committed.
