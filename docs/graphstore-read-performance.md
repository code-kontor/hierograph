# GraphStore Read Performance — Parquet vs Neo4j

**Date:** 2026-11-06
**Test:** `io.hierograph.itest.graphstore.GraphStoreReadPerformanceIT`
**Question:** How long does it take to create Hierograph's type-level in-memory graph from a Parquet snapshot, instead of from the jQAssistant Neo4j database?

---

## TL;DR

Building the type-level in-memory graph from the Parquet snapshot is **~900× faster** than building it from Neo4j via the existing `jQAssistantMapping` Cypher:

| Path | Median | Range (min–max) |
| --- | ---: | ---: |
| **Parquet → GraphStore** | **80.3 ms** | 69.9 – 100.2 ms |
| **Neo4j → graph rows** | **71,311.9 ms** (~71 s) | 70,159.8 – 73,643.1 ms |

The entire snapshot is **≈ 3.4 MB** on disk, and producing it from Neo4j is a **one-time ≈ 2.9 s** export. After that, every cold start reads from Parquet in well under 100 ms.

---

## Why this matters

The driver for replacing jQAssistant + Neo4j with an own scanner, Parquet snapshots and an in-memory GraphStore is **cheap, repeatable graph construction** — for cold starts, rebuilds, and incremental updates while Hierograph stays online. This benchmark quantifies the cold-start half of that promise: the per-build cost of the Neo4j path is exactly the cost the migration removes.

---

## What was measured

Both paths produce the **equivalent type-level graph** — proven separately by `GraphStoreVsGraphDbMappingIT`, which diffs the two projections by FQN and finds type dependencies identical and the hierarchy identical modulo 10 documented package rows.

- **Parquet → GraphStore:** `ParquetSnapshotLoader.load(dir, GraphStore(JQA_ALIGNED))` — reads the `types` / `members` / `refs` Parquet tables (via Hardwood), reconstructs one `ClassFacts` per type, and feeds them through `GraphStore.applyClass` + a single `resolvePlaceholders()`.
- **Neo4j → graph rows:** `GraphDbMappingReference(boltClient).build()` — runs the existing `jQAssistantMapping` rules (top-level, parent-child, and the `DEPENDS_ON` dependency rule) over Bolt and resolves Neo4j ids to FQNs, assembling the same `HierarchyRow` / `TypeDependency` rows.

Both are **type-level**: member nodes are not materialized in either (the GraphStore keeps them in the snapshot as an on-demand concern; the reference skips the `FIELD`/`METHOD` rules). The `PlaceholderPolicy.JQA_ALIGNED` policy is applied on the Parquet side so external/placeholder shapes match jQAssistant's `:Virtual` layer.

### Methodology

1. **Export once (not in the measured path):** the Neo4j store is exported to a Parquet snapshot in a temp directory. This Neo4j→Parquet cost is reported separately.
2. **Warm up:** each path is built `WARMUP` times before timing (JVM JIT on the Parquet side; Neo4j page cache / driver on the other).
3. **Measure:** each path is built `RUNS` times with `kotlin.system.measureNanoTime`; min / median / mean / max are reported.
4. **Guard, don't assert timing:** the test asserts only that both paths produce a non-empty, comparable graph. Wall-clock thresholds are intentionally **not** asserted — they would be flaky across machines and CI.

> The numbers in this report were produced with `RUNS = 7`, `WARMUP = 2` (9 Neo4j builds total ⇒ ~677 s wall-clock). The committed test now uses `RUNS = 3`, `WARMUP = 1` to keep routine runs shorter; the per-build figures are unaffected.

---

## Results

### Build time (type-level in-memory graph)

```
runs = 7 (after 2 warmup)        [ min  /  median  /  mean  /  max ]  ms
  Parquet -> GraphStore :     69.9  /   80.3  /   81.1  /  100.2
  Neo4j   -> graph rows :  70,159.8 / 71,311.9 / 71,534.7 / 73,643.1
  speedup (median Neo4j / median Parquet): 887.6x
```

### One-time export (Neo4j → Parquet)

| Step | Time |
| --- | ---: |
| Export store → `types`/`members`/`refs` Parquet | **2,861 ms** |

### Snapshot size

| File | Bytes | Approx |
| --- | ---: | ---: |
| `types.parquet` | 141,675 | 142 KB |
| `members.parquet` | 2,858,899 | 2.86 MB |
| `refs.parquet` | 591,727 | 592 KB |
| **Total** | **3,592,301** | **≈ 3.4 MB** |

### Graph size (sanity)

| Projection | Nodes / rows | Type dependencies |
| --- | ---: | ---: |
| Parquet → GraphStore | 11,074 nodes | 99,339 |
| Neo4j → graph rows | 10,643 hierarchy rows | 136,245 |

The row counts differ because the GraphStore aggregates dependencies per type-pair while the reference list is flatter, and the two hierarchies differ by the 10 documented package/type rows. The **set-level** equivalence (what acceptance checks) is validated by `GraphStoreVsGraphDbMappingIT`; these raw counts are reported only as a non-emptiness sanity check.

---

## Analysis

- **The Neo4j path is query-bound, not transfer-bound.** ~71 s per build is dominated by the `DEPENDS_ON` dependency rule, which evaluates three `EXISTS` subqueries per edge over the full graph:

  ```cypher
  MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type)
  WITH ..., EXISTS { (t1)-[:ANNOTATED_BY]->(:Annotation)-[:OF_TYPE]->(at)
                     WHERE at = t2 OR (at)-[:RESOLVES_TO]->(t2) } AS isAnnotatedBy
  RETURN ..., EXISTS { (t1)-[:EXTENDS]->(t2) },
              EXISTS { (t1)-[:IMPLEMENTS]->(t2) }, isAnnotatedBy, ...
  ```

  On thousands of types and tens of thousands of `DEPENDS_ON` edges this is the cost center. It is also remarkably stable (min→max spans only ~3.5 s), i.e. it is steady-state work, not warmup noise.

- **The Parquet path is dominated by nothing in particular** — it reads ~3.4 MB and builds HashMaps. 80 ms median, tight distribution. There is no query engine to pay for; the GraphStore *is* the query engine and it is constructed directly.

- **`members.parquet` is pure overhead for a type-level cold start.** At 2.86 MB it is ~80% of the snapshot, yet members are not materialized in the in-memory graph — the loader currently reads the table and discards it. Skipping `members.parquet` for type-level startup (loading it lazily only when detail is requested) would shrink the read further. Even without that optimization the path is sub-100 ms.

- **The one-time export (2.9 s) is amortized immediately.** It is paid once to produce a snapshot that then loads ~900× faster than re-querying Neo4j, and it can itself be done off the hot path.

---

## Threats to validity

- **Single corpus:** one jQAssistant store (a scan of the Spring jars via the integration-test image). Absolute times scale with graph size; the ~900× ratio is specific to this corpus but the *shape* (milliseconds vs tens of seconds) is driven by the `EXISTS`-heavy query and is expected to hold broadly.
- **Not the full production model:** the comparison is type-level and uses `GraphDbMappingReference` (row assembly), not the full `DefaultMappingService.convert` → `HGModel`. The production Neo4j build does at least as much work, so this is a conservative (favorable-to-Neo4j) baseline.
- **Warm Neo4j:** the database was warmed before timing, so these are steady-state, not first-touch, numbers.
- **Environment:** Docker Desktop (Server 29.8.2, 40 GB allotted), JDK 21.0.11, `neo4j-java-driver` 6.2.0, Hardwood `1.1.0.Beta3`, macOS. Testcontainers starts the jQA image fresh per run; container startup (~5 s) is outside the measured window.

---

## Reproduce

Requires Docker (the integration-test jQA image is built and run via Testcontainers):

```bash
mvn -Pitest -pl hierograph-itest/io.hierograph.itest.tests -am verify \
    -Dit.test=GraphStoreReadPerformanceIT
```

The test prints the full report (snapshot sizes, graph sizes, timings, speedup) to stdout before the assertions.

---

## Detail-layer read performance (scoped member queries)

*Added 2026-11-06 — `DetailDiffAndBenchmarkIT`, same full Spring scan.*

The numbers above are a **cold-start** comparison (build the whole type graph once). The **detail layer** answers a different, on-demand question — member-level `detail_dependencies` scoped to a type or package — and was the last piece still tied to Neo4j (the detail MCP tools query it directly). The prototype serves it from a `detail_refs.parquet` (member edges — calls / reads_field / writes_field / overrides / throws / returns / has_type / parameter_type / annotated_by; **633,377 edges, ~22 MB** on the Spring scan) via Hardwood predicate push-down, with inheritance scope resolved in-memory from the type graph.

### Correctness (diff vs Neo4j)

For each scope the engine's edge set is compared against the *same* member-edge Cypher run directly against Neo4j (`JqaDetailReference`), both fed the identical inheritance-expanded FQN set. Exact match across all scopes:

| Scope | types in scope | edges | diff (only-Parquet / only-Neo4j) |
| --- | ---: | ---: | :---: |
| `DefaultListableBeanFactory` | 17 | 6,082 | 0 / 0 |
| `ClassReader` | 2 | 1,355 | 0 / 0 |
| `AbstractBeanFactory` | 10 | 2,621 | 0 / 0 |

A relationship-filtered query (`calls`) also matched the baseline exactly — so the export is faithful, inheritance scope resolution matches jQA's `EXTENDS|IMPLEMENTS*0..` expansion, and Hardwood's row-group skipping drops no valid rows.

### Timings (median, 3 runs)

| Scope | Parquet | Neo4j | speedup |
| --- | ---: | ---: | ---: |
| `DefaultListableBeanFactory` (17 types, 6,082 edges) | 14.6 ms | 72.0 ms | 5× |
| `ClassReader` (2 types, 1,355 edges) | 10.5 ms | 18.3 ms | 2× |
| `AbstractBeanFactory` (10 types, 2,621 edges) | 10.9 ms | 33.1 ms | 3× |

### Reading these numbers honestly

- **Scoped detail queries are 2–5× faster on Parquet — a modest, *expected* margin.** Unlike the cold-start build (~900×), a scoped detail query is exactly where Neo4j is strong: a small, index-anchored traversal already in the tens-of-ms range. The win is **matching it from our own files with no Neo4j and no second engine**, not beating it by orders of magnitude.
- **The Parquet figures have clear headroom.** The prototype reads the full ~22 MB `detail_refs.parquet` **uncompressed** per query (row-group skipping on `from_type`, but no footer/page caching yet). ZSTD + footer caching + tighter row-group sizing should pull the 10–15 ms down further.
- **Prototype scope.** Reverse relationships (`read_by` / `written_by`), member display enrichment (join to `members.parquet`), and keyset cursors are not yet implemented; none affects the edge-set correctness measured here.

Reproduce (needs Docker): `mvn -Pitest -pl hierograph-itest/io.hierograph.itest.tests -am verify -Dit.test=DetailDiffAndBenchmarkIT`.

---

## Conclusion

Two complementary results, both against the full Spring scan:

- **Cold start / rebuild (type level):** loading the type graph from a Parquet snapshot is **~900× faster** than rebuilding it from Neo4j (median 80 ms vs ~71 s), against a one-time 2.9 s export and a 3.4 MB snapshot.
- **On-demand detail (member level):** scoped `detail_dependencies` queries are served from Parquet with **exact** edge-set parity to Neo4j and a **2–5×** speedup — replacing the last Neo4j-backed path with no second engine.

Together they confirm the core premise of the scanner/snapshot/GraphStore migration: once facts live in Parquet, both standing up Hierograph's in-memory model and answering member-level queries are at least as fast as — and for cold start, dramatically faster than — re-querying jQAssistant's graph, while owning the whole model.
