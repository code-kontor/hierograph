# Hierograph Build Manifest — Specification

The build manifest is the contract between a Maven build and the Hierograph MCP
server's **manifest mode**. The Maven plugin's `hierograph:create-descriptor` and
`hierograph:merge-descriptors` goals write it; the server's manifest-mode graph
source reads it, scans each module's compiled output itself, and watches those
output directories for changes.

The manifest records only what the build tool knows and the server cannot
reconstruct on its own: each module's coordinates, its compiled-output
directories, and its **resolved dependency classpaths in Maven order**. The server
does the bytecode scanning; the manifest tells it *what* to scan and *against
which classpath to resolve references*.

- **Producer:** `hierograph:merge-descriptors` (merging per-module `module.json` descriptors)
- **Consumer:** the server's manifest-mode graph source (`hierograph.graph.source=manifest`)
- **Location:** `<reactor-root>/.hierograph/manifest.json`
- **Encoding:** UTF-8 JSON

---

## 1. Format

```json
{
  "schemaVersion": 1,
  "generator": "io.hierograph.maven.plugin/0.2.0",
  "generatedAt": "2026-11-08T17:04:11Z",
  "reactorRoot": "/ws/billing",
  "modules": [
    {
      "gav": "com.example:billing-core:1.4.0",
      "name": "billing-core",
      "mainOutputDir": "/ws/billing/billing-core/target/classes",
      "testOutputDir": "/ws/billing/billing-core/target/test-classes",
      "sourceRoots": ["/ws/billing/billing-core/src/main/java"],
      "testSourceRoots": ["/ws/billing/billing-core/src/test/java"],
      "classpath": [
        { "gav": "com.example:billing-api:1.4.0",         "internal": true,  "jar": null },
        { "gav": "com.google.guava:guava:33.0.0-jre",     "internal": false, "jar": "/home/u/.m2/…/guava-33.0.0-jre.jar" }
      ],
      "testClasspath": [
        { "gav": "com.example:billing-api:1.4.0",          "internal": true,  "jar": null },
        { "gav": "com.google.guava:guava:33.0.0-jre",      "internal": false, "jar": "/home/u/.m2/…/guava-33.0.0-jre.jar" },
        { "gav": "org.junit.jupiter:junit-jupiter:5.11.0", "internal": false, "jar": "/home/u/.m2/…/junit-jupiter-5.11.0.jar" },
        { "gav": "org.assertj:assertj-core:3.26.3",        "internal": false, "jar": "/home/u/.m2/…/assertj-core-3.26.3.jar" }
      ]
    }
  ]
}
```

### Top level

| Field | Type | Meaning |
|-------|------|---------|
| `schemaVersion` | int | Manifest schema version (currently `1`). Advisory — see §4. |
| `generator` | string | `io.hierograph.maven.plugin/<version>`; informational. |
| `generatedAt` | string | ISO-8601 instant the manifest was written; informational. |
| `reactorRoot` | string | Absolute path of the reactor root on the build host. The anchor for path portability (§3). |
| `modules` | array | One entry per reactor module with compiled output, sorted by `gav`. |

### Module

| Field | Type | Meaning |
|-------|------|---------|
| `gav` | string | `groupId:artifactId:version` — the module's artifact key and node identity. |
| `name` | string | Display name (the `artifactId`). Falls back to `gav` if absent. |
| `mainOutputDir` | string? | Absolute path of the compiled main classes (`target/classes`). |
| `testOutputDir` | string? | Absolute path of the compiled test classes (`target/test-classes`). |
| `sourceRoots` | string[] | Absolute main source directories. Recorded; reserved (§5). |
| `testSourceRoots` | string[] | Absolute test source directories. Recorded; reserved (§5). |
| `classpath` | entry[] | The module's **main** classpath — its non-test dependencies in resolution order (§2). |
| `testClasspath` | entry[] | The module's **test** classpath — all dependencies incl. test scope, in resolution order (§2). Omitted when there is no test output. |

### Classpath entry

| Field | Type | Meaning |
|-------|------|---------|
| `gav` | string | `groupId:artifactId:version` of the dependency. |
| `internal` | boolean | `true` = a reactor sibling (scanned from its own output); `false` = an external dependency. |
| `jar` | string? | Resolved local path of an external dependency's jar. Recorded; reserved (§5). |

---

## 2. Classpaths

A reference in bytecode carries only a bare type name; when several artifacts on
the classpath define that name, resolution must pick the one the code was actually
compiled against. The manifest therefore records each module's classpath **in
Maven resolution order**, and the server builds a per-owner classpath-order type
resolver from it (first definer on the owner's classpath wins).

**Two classpaths, because main and test code resolve differently.**

- **`classpath` (main)** — every resolved dependency whose scope is **not** `test`:
  `compile`, `runtime`, `provided`, and `system`. Main bytecode is resolved against
  it. (`provided` and `system` matter: a class compiled against a `provided` API
  references its types, so dropping them would leave those references dangling as
  external placeholders.)
- **`testClasspath` (test)** — the **full** test classpath: every resolved
  dependency, test scope included (`compile` + `runtime` + `provided` + `system` +
  `test`), in resolution order. Test bytecode (JUnit, AssertJ, Mockito, …) is
  resolved against it. It is recorded as the complete list, not a delta over
  `classpath`, so the consumer never has to merge two lists.

**Entries list dependencies only; the owner is implicit.** Neither list contains
the module itself — the consumer prepends the owner as index 0 (the owner's own
types resolve first). Concretely, the resolver order the server builds is:

- main artifact `g:a:v` → `[g:a:v]` + `classpath[].gav`
- test artifact `g:a:v:test` → `[g:a:v:test, g:a:v]` + `testClasspath[].gav`

(The test artifact is prepended with both itself and the **main** module, so a test
type resolves against its own output, then the module under test, then the test
classpath.)

---

## 3. Path portability

Every path in the manifest — `reactorRoot`, `mainOutputDir`, `testOutputDir`,
`sourceRoots`, and each entry's `jar` — is an **absolute path on the build host**.
The manifest is nonetheless portable across machines by one rule:

> The manifest always lives at `<reactorRoot>/.hierograph/manifest.json`. The
> consumer derives the **local** reactor root as the manifest file's
> grandparent directory, and re-roots any recorded path that lies under the
> recorded `reactorRoot` onto that local root.

On the build host the recorded and local roots are equal, so re-rooting is the
identity. When the server runs elsewhere — e.g. in a container with the workspace
mounted at a different path — the two roots differ and every in-reactor path
(the output directories, reactor-sibling sources) is relocated correctly.

**Limitation:** a path **outside** the reactor tree is left exactly as recorded.
External dependency `jar` paths (typically under `~/.m2`) therefore do not
relocate, which is one reason the consumer resolves dependencies by `gav`, not by
`jar` (§5).

---

## 4. Parsing & compatibility

The consumer parses the manifest **leniently**, which defines the compatibility
contract:

- **Unknown fields are ignored.** A newer producer may add fields a older consumer
  does not know; they are skipped, not errors.
- **Missing fields take defaults.** Absent strings default empty, absent arrays
  empty, `internal` defaults `false`, `jar`/dirs default null, `schemaVersion`
  defaults `0`. An older producer's manifest still loads.

Together these make the format forward- and backward-compatible, so additive
changes (such as `testClasspath`) need no coordinated upgrade.

`schemaVersion` is therefore **advisory**: it is recorded for diagnostics and for a
future hard-incompatibility signal, but the consumer does not reject a manifest on
version alone. Bump it only on a genuinely incompatible shape change, and gate new
behavior on the presence of fields rather than on the version number.

---

## 5. Recorded but reserved

Two fields are captured by the producer and carried in the manifest but **not yet
consumed** — recorded now so the format need not change when they are:

- **`jar`** (classpath entries) — the resolved local path of an external
  dependency. Reserved for API-scanning libraries the server has not cached, so
  references resolve *into* third-party types instead of stopping at a placeholder.
  Today the server resolves dependencies by `gav` only (and the path would not
  survive relocation anyway, §3).
- **`sourceRoots`** / **`testSourceRoots`** (modules) — the module's main and test
  source directories, reserved for mapping graph nodes back to their source
  locations.

A consumer must tolerate their presence and absence; a producer should populate
them when it cheaply can.

---

## 6. How the server consumes the manifest

In manifest mode the server:

1. Reads the manifest and builds the `PathRemap` from `reactorRoot` (§3).
2. For each module: records its `classpath` (keyed by `gav`), re-roots and scans
   `mainOutputDir` with the ASM bytecode scanner; a module whose output directory
   is missing is skipped with a warning. (It is an error for *no* module to have
   scannable output.)
3. When test scanning is enabled: re-roots and scans `testOutputDir` as the
   artifact `<gav>:test`, resolved against `testClasspath`.
4. Installs the per-owner classpath-order type resolver (§2) and ingests the
   scanned classes straight into the in-memory graph store; the Parquet detail
   snapshot is written separately, off the hot path.
5. Watches each scanned output directory and re-scans only the modules whose
   classes change, rebuilding the graph from the cached union.

The manifest is thus a *scan plan plus resolution context*: it never contains
scanned facts, only the inputs the server needs to produce them.

---

## 7. Conformance

The format above is the target contract. Current status:

- **Implemented:** the envelope, `classpath` (main), the path-portability rule,
  lenient parsing, main/test scanning, the classpath-order resolver, and
  directory watching.
- **Pending (this spec's additions):** `testClasspath` — the producer must emit it
  (resolving test scope) and the consumer must resolve `<gav>:test` against it
  instead of reusing the main classpath; widening the **main** `classpath` to
  include `provided`/`system` scopes; and emitting `testSourceRoots` (from the
  module's test source roots) alongside the existing `sourceRoots`. Until then,
  test references to test-scope dependencies and main references to `provided`
  types resolve to external placeholders, and no test source roots are recorded.
