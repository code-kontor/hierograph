# Integration Test Strategy — Real-World Graph Fixtures

> Status: **discussion / proposal**, not yet implemented. Captures the plan for an integration test
> suite that builds the hierarchical graph from a real codebase instead of handmade fixtures.

## Goal

Today the tests build handmade graphs (`HGGraphFactory.createNode` / `createCoreDependency`,
`AlgorithmTestGraph`, etc.). These are precise but unrealistic, and they never exercise the parts of
the system that actually broke in practice. The idea is an integration suite that drives the **real
pipeline end to end** on a fixed real-world codebase (e.g. the Spring Framework at a pinned version):

```
jars → jQAssistant scan → Neo4j → bolt → DefaultMappingService.convert → HGModel → algorithms / tools / GraphQL
```

This is also the layer where recent bugs lived (the `graph_overview` Main+Test double-add, the
top-level dedup) — none of those reproduce on handmade graphs.

> Note: serialization (`HGGraphJson` snapshots) is deliberately **not** used as the fixture source. A
> snapshot would only exercise the in-memory layers and bypass the scanner / Neo4j / mapping path,
> which is exactly the part we want realism in.

## The one hard constraint that shapes everything: node identity

`HGNode.identifier` is the Neo4j `id(n)` (see the `RETURN id(a)` queries in
`JQAssistantHierarchyProvider`). Neo4j internal ids are **not stable across scans** (and `id()` is
deprecated in favor of `elementId`). Therefore:

- **Assert by qualified name, never by numeric id.** Provide a fixture helper such as
  `findByFqn(model, "org.springframework.core.io.Resource")`, and write assertions over FQNs and
  relationships, not ids.
- Golden/approval files must be **FQN-keyed**, not id-keyed.
- Collection ordering from Cypher is not guaranteed — sort before asserting.
- A published fixture image freezes the store, so Neo4j ids are stable *within a tag* — convenient, but
  any regeneration churns them. Keep assertions FQN-keyed regardless; treat id-stability as a
  non-contract.

This assertion style should be locked down first, because it constrains everything else.

## Provisioning the scanned database — a published fixture image

The scan is expensive and its *result* is what we want to test against, so bake the scanned Neo4j into
a **prebuilt Docker image** and publish it to a registry. Tests pull the image, boot it with
Testcontainers, connect over bolt, and run the real `DefaultMappingService` pipeline against it. This
splits cleanly into two tiers:

- **Primary tier — published fixture image (fast, every PR).** A Neo4j image with the jQAssistant scan
  of a pinned codebase already loaded. CI just pulls + boots it (seconds); the data is byte-identical
  everywhere, pinned by image tag. It exercises the real bolt → provider-Cypher → `DefaultMappingService`
  → lazy `GraphDbNodeSource` path, and keeps no large binaries in git.
- **Scanner tier — regeneration job (nightly / manual).** A separate job re-scans the codebase from
  scratch and rebuilds the image. This is the only thing that validates the *scanner itself*
  (jars → graph): the primary tier freezes the scan result, so the act of scanning is re-tested only
  when the image is regenerated — and the regeneration job *is* that test.

This is the productized form of "scan once, distribute the result" — strictly better than checking a
dump into git, because the registry handles distribution and the tag pins the codebase, jQAssistant,
and Neo4j versions together.

**Registry:** prefer **GHCR** (`ghcr.io/code-kontor/…`) over Docker Hub — same GitHub org as the repo,
no anonymous pull-rate-limit pain in CI, auth via `GITHUB_TOKEN`.

**Reproducible build:** build the image from a committed `Dockerfile` + `scan.sh` (`FROM neo4j:5.x`;
copy the scanned store, or `neo4j-admin database load` a dump), **not** `docker commit`. Tag with all
coupled versions, e.g. `hierograph-itest-fixture:spring-6.1.14-jqa-2.0.x-neo4j-5.x`. The Neo4j version
is coupled to the on-disk store format, so bake and pin them together; regenerate (new tag) when the
codebase or the jQAssistant/Neo4j version changes.

**Testcontainers** boots it and owns lifecycle:

```java
new Neo4jContainer<>(
    DockerImageName.parse("ghcr.io/code-kontor/hierograph-itest-fixture:<tag>")
        .asCompatibleSubstituteFor("neo4j"))
```

Take the mapped bolt URI, feed it to `IBoltClientFactory`, build the `HGModel`. CI runners must have
Docker (GitHub Actions runners do).

## Boot the fixture once per suite

Booting the container and running `convert` is not free, so build the `HGModel` **once** and share it
across all test methods: `@TestInstance(PER_CLASS)` + `@BeforeAll` (start the container, connect,
`convert`), then many lightweight assertion methods. A singleton/suite-scoped container plus a
memoizing `SpringFixture` holder is the natural shape.

## Build / module structure

- A dedicated module (`hierograph-itest`) depending on `core.model`, `core.algorithms`, `mcp.server`,
  `graphql`, and the jQAssistant provider.
- **maven-failsafe** (`integration-test` phase) + JUnit `@Tag("integration")`, gated behind a Maven
  profile so `mvn test` stays fast and the heavy suite runs in `mvn verify -P it` / CI. The existing
  jQAssistant Maven-plugin profile and `hierograph-jqassistant` module can be built on.
- Spring jars as **test-scoped, pinned dependencies** (resolved into `~/.m2`); point the scanner at
  the resolved files via `dependency:copy-dependencies`. Do not commit jars.

## Fixture choice

Spring is the right mental benchmark (the docs already cite a "Spring-sized: ~116k edges" reference),
but full Spring + transitive deps is a lot to scan on every key change. Start with a **curated, pinned
subset** — e.g. `spring-core` + `spring-beans` + `spring-context` at a fixed version — which is
realistic, multi-module, has real cross-module dependencies, and scans quickly. Promote to full Spring
as a separate **nightly / scale** suite.

### Jar scan vs Maven-reactor scan — this changes what is testable

The shape jQAssistant produces depends on *what* you scan, and that determines which provider query
branches the fixture exercises:

- **Loose jars** → `:Artifact:Jar` top-level nodes → the provider's **jar branch** runs. Covers
  structure, dependencies, algorithms, and tools on real data. Simple to produce (just the jars).
- **A built multi-module Maven reactor** → `:Project:Maven:Directory -[CREATES]-> :Artifact:Main/Test`
  → the provider's **Maven branch** runs. This is the real-world primary path (the DARE graph is a
  reactor scan), and it is the **only** shape that exercises the Main+Test double-add dedup — the
  branch the recently-fixed `graph_overview` duplicate-hierarchy bug lived in. A loose-jar fixture
  cannot cover that regression.

Implication: likely **two baked fixtures** — a Spring (jar) image for scale/realism, and a small
built multi-module Maven project for the Maven-branch + dedup regressions.

Repo hygiene: don't commit the jars (Maven resolves them) and don't commit the scanned store — it
lives in the published image, not in git.

## What becomes testable that isn't today

- **Real module coupling**: `spring-context → spring-core/spring-beans` weights; `pairwise_dependencies`
  DSM + cycle / topological-order over real modules (Spring is mostly acyclic at module level → good
  `has_cycles == false` + layering assertions; or pick a known-cyclic set).
- **The scanner / mapping layer**: Main+Test double-artifact dedup, top-level dedup, package nesting —
  the `graph_overview` duplicate-hierarchy bug would have been caught here.
- **Lazy loading**: `GraphDbNodeSource.properties` / `labels` actually fetched over bolt (only a real
  Neo4j exercises this).
- **Scale / pagination**: `pairwise_dependencies` soft cap + cursor paging on a genuinely large edge
  set; tool response sizes.
- **Tool / GraphQL end to end** on realistic data.

For brittle counts (which drift with Spring patch versions), prefer **presence / relationship
assertions** and **FQN-keyed approval files** over exact integer counts, and pin the Spring version
hard.

## A concrete first slice

1. `hierograph-itest` module, failsafe + `@Tag`, profile-gated.
2. Build and publish the fixture image (`Dockerfile` + `scan.sh`) for a pinned codebase; `SpringFixture`
   boots it via Testcontainers → connect over bolt → `convert` → `HGModel`, memoized, with FQN lookup
   helpers. (Use a Maven-reactor fixture if you want the dedup regression below; a Spring jar image
   otherwise.)
3. ~5 assertions:
   - a known module dependency (e.g. `spring-context → spring-core`),
   - a `graph_overview` sanity check (no duplicate top-level entries — a direct regression for the
     recently-fixed bug),
   - a `pairwise_dependencies` DSM over the three modules,
   - an `outgoing_dependencies` detail-level query to prove bolt lazy-loading works.

That proves the harness end to end and immediately locks in the regressions; everything else is
additive.

## Open questions to settle before prototyping

1. **Registry & CI** — GHCR (`ghcr.io/code-kontor/…`) acceptable, and is Docker available on the CI
   runners (Testcontainers prerequisite)?
2. **Fixture content** — a Spring **jar** image, a small **Maven-reactor** image, or **both** (the
   reactor one is required to cover the Main+Test dedup regression)?
3. **Fixture size** — start with a 3-module Spring subset (fast), or go straight to full Spring
   (scale, slower)?
4. **Regeneration cadence** — nightly vs. manual/tagged, and where the image build+push lives (a
   dedicated CI job / `fixtures/` dir with `Dockerfile` + `scan.sh`).
5. **Where it lives / when it runs** — separate `hierograph-itest` module + CI-only profile (default),
   or runnable in the normal build?
