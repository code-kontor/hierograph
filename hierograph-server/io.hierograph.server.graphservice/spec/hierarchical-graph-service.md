# Hierarchical Graph Service — Specification

`HierarchicalGraphService` is the Hierograph MCP server's single source of truth
for the loaded code graph. Everything the server answers — navigation, search,
dependency and detail queries — reads from the one graph this service owns. It
builds that graph from the configured source, serves it as an atomically
swappable snapshot, keeps it current as the underlying code changes, and does so
without ever handing a tool a half-built or inconsistent view.

It is a Spring `@Service`: one instance per server, built at startup and held for
the server's lifetime.

---

## 1. Why it exists

The MCP tools should not know *where* the graph's facts come from or *when* they
change. A reference to "the current graph" must always resolve to a complete,
self-consistent set of (model, search index, fingerprint), even while a newer
graph is being built in the background. And the expensive part — reading the
member-level "detail" tables from Parquet — must stay off the request hot path.

This service concentrates those concerns in one place so the rest of the server
stays simple:

- **One consistent view.** Tools read a model, a search index and a data hash that
  always belong to the same build; a reload can never expose a mix of old and new.
- **Source independence.** The same served interface is produced whether the graph
  was loaded from a pre-built snapshot or scanned live from a build manifest.
- **Live refresh.** In local development the graph follows the code: the service
  watches the compiled output and rebuilds incrementally, with no server restart.
- **No stale detail.** Member-level detail is coupled to the exact graph build it
  belongs to; while a rebuild is in flight, detail is *withheld*, never served from
  the previous build.

---

## 2. What it covers

**Owns:**

- Building the graph from the configured source and bridging it to the served
  hierarchical model.
- Holding the current graph as an immutable snapshot and swapping it atomically.
- Rebuilding on demand (`reload`) and, in manifest mode, automatically on change.
- Coupling the Parquet detail source to the graph generation it was built for, and
  publishing it only when it is current.
- Degrading gracefully: starting empty rather than failing, and keeping the last
  good graph when a rebuild fails.

**Does not own** (collaborators it delegates to):

- *Scanning bytecode and resolving a build manifest* — `ManifestGraphSource`.
- *Reading/writing Parquet tables* — `ParquetReader` / the snapshot writer.
- *Bridging a type-level store to the hierarchical model* — `GraphStoreToHGModel`.
- *Watching the filesystem* — the polling / native watchers.
- *Answering tool requests* — the MCP tools, which consume this service's output.

---

## 3. Core concepts

**Snapshot.** The unit the service serves and swaps. A `GraphSnapshot` bundles,
for one build: the hierarchical `model`, its `searchIndex`, a `dataHash`
fingerprint of the model, the optional type-level graph store, a member-handle
index, and the build `generation`. It is immutable; the service never mutates a
live snapshot, it builds a new one and replaces the old.

**Atomic swap.** The current snapshot lives behind a single atomic reference.
Every public read resolves it once, so concurrent tool calls each see one coherent
(model, index, hash) set. A rebuild publishes by replacing that reference in a
single step — there is no window in which a reader sees a partial graph.

**Generation.** A monotonic counter. Each build takes the next value and stamps
both its snapshot and the detail source built for it. The generation is how the
service tells "current" from "superseded" when work happens asynchronously.

**Detail source.** Member-level edges (calls, field access, …) are read from
Parquet, not held in the navigation model. A `DetailSource` pairs a Parquet
directory (and the load-derived cross-artifact edges) with the generation it was
built for. It is served **only** while its generation matches the live snapshot's;
otherwise the service reports "rebuilding" and withholds it. This is what
guarantees a query never returns detail from a stale build.

**Data hash.** A fingerprint of the served model, changing on every successful
reload. Pagination cursors carry it; when the graph is replaced, cursors issued
against the previous model no longer match and must be re-requested.

---

## 4. Source modes

The source is chosen by `hierograph.graph.source` and decides how a build produces
its snapshot. The *served* interface is identical either way.

| | `parquet` (default) | `manifest` |
|---|---|---|
| **Input** | a pre-built Parquet snapshot directory | a build manifest (`merge-descriptors` output) |
| **Build** | read the tables into a store, bridge to the model | scan the named modules and ingest straight into the store, in memory |
| **Detail** | published **synchronously** with the snapshot (ready at once) | Parquet snapshot written **asynchronously** on a background thread, published when done |
| **Refresh** | explicit `reload` after the snapshot is rewritten | automatic: a file watcher rebuilds on change |
| **For** | CI / a shared server consuming a published snapshot | local development against a live build |

In manifest mode the navigation graph goes live immediately from the in-memory
ingest — it does not wait on Parquet I/O. The detail snapshot catches up in the
background (§6).

---

## 5. Lifecycle

**Startup** builds the graph best-effort and **never fails the server**. If the
source cannot be loaded (e.g. no snapshot yet), the service starts empty and logs
it; tools then report that no graph is available until one is loaded. On success it
publishes the snapshot, its detail, and starts watching if enabled.

**Reload** rebuilds from the configured source and swaps atomically, without
restarting the server. The new snapshot is built **fully before** the swap, so a
failed reload leaves the previously loaded graph in place and returns an `error`
status rather than emptying the server. Reload returns a small status map
(`status`, `root_children`, `search_index_entries`, `data_hash`, `reload_millis`)
for the `reload_graph` tool. Outstanding pagination cursors become stale across a
reload (their data hash no longer matches).

**Shutdown** stops the watcher and the background executors.

---

## 6. Live updates (manifest mode)

When watching is enabled, the service tracks the manifest file and every output
directory the manifest declares, including directories that do not exist yet, and
applies changes on a dedicated single-thread updater (kept off the watcher's own
event thread):

- **A module's output changed** → rescan only the changed modules, rebuild the
  graph from the cached union of per-module scans, and swap it in. Unchanged
  modules are not re-scanned. An output directory that appears (a module compiled
  for the first time) is scanned and added; one that disappears drops the
  module's classes.
- **The manifest itself changed** (module set or classpath) → re-read it fully,
  rebuild, and re-target the watcher at the new module set.

Watch mode is `polling` (default), `native`, or `off`, with configurable poll
interval and a settle delay that coalesces a burst of edits into one rebuild. The
polling watcher sees a directory appear as a signature change. A native watch can
only be registered on an existing directory, so the native watcher checks on each
tick which declared directories exist, reports the ones that appeared or
disappeared, and re-registers. An incremental update that fails logs and keeps
the previous graph.

**Starting without output.** When the manifest is readable but no declared output
exists, or the graph cannot be built, the service starts without a graph and
still starts the watcher; the first output that appears builds the graph. A
failed `reload` or manifest reload also re-targets the watcher, so the service
follows a new module set even before any of it is compiled.

**Asynchronous detail.** Each manifest build schedules its Parquet detail write on
a background writer. When the write for a generation finishes, it is published as
the detail source **only if no newer build has started meanwhile** — otherwise the
work is discarded. Superseded snapshot directories are pruned; only the live
generation's directory is kept.

---

## 7. The "rebuilding" contract for detail

Because detail lags the live graph in manifest mode, detail access is guarded:

- `readyDetailDir()` returns the Parquet directory **only** when its generation
  matches the live snapshot; otherwise `null`. A detail tool must treat `null` as
  *rebuilding* and must **not** fall back to a previous directory.
- `derivedDetailRefs(…)` — the load-computed cross-artifact edges (§8) — applies
  the same guard, returning nothing while detail is not current.

The rule is deliberate: it is better to answer "detail is rebuilding, retry" than
to answer with edges from a build the navigation graph no longer reflects.

---

## 8. Load-derived cross-artifact edges

Some member edges — overrides, and virtual / resolved calls — depend on the
*whole* assembled graph and cannot be derived from a single artifact scanned on its
own. The service computes these at load time over the complete graph and keeps only
the edges not already present in the snapshot's detail tables. A complete
single-shot snapshot therefore contributes nothing extra, while a set of
per-artifact snapshots gains exactly its cross-artifact edges. Detail tools union
these derived edges with the Parquet `detail_refs` for a scope.

---

## 9. Served interface

What tools read from the service (all reads resolve the current snapshot atomically):

| Member | Purpose |
|--------|---------|
| `isGraphAvailable` | whether any graph is loaded; tools check this before answering |
| `model` | the hierarchical model (navigation, hierarchy, dependencies) |
| `searchIndex` | the node search index for the current model |
| `dataHash` | fingerprint of the current model; the basis for pagination-cursor validity |
| `graphStore` | the type-level store backing the snapshot, when present |
| `memberHandleIndex` / `memberHandle(key)` | map member node handles ↔ member keys for the detail tools |
| `readyDetailDir()` | the Parquet detail directory, or `null` while rebuilding (§7) |
| `derivedDetailRefs(from, to, relationship)` | load-derived cross-artifact edges for a scope (§8) |
| `parquetDirectory` | directory the node-property provider reads (may lag during a rescan) |
| `reload()` | rebuild and swap; returns a status map |
| `seed(model)` | install a ready-made model directly — for tests, bypassing any source |

---

## 10. Configuration

All properties are optional; defaults suit a local server.

| Property | Default | Meaning |
|----------|---------|---------|
| `hierograph.graph.source` | `parquet` | `parquet` or `manifest` (§4). |
| `hierograph.graph.parquetDir` | `parquet` | Snapshot directory, `parquet` mode. |
| `hierograph.graph.manifest` | `.hierograph/manifest.json` | Build manifest, `manifest` mode. |
| `hierograph.graph.include-tests` | `false` | Also scan each module's test output (`manifest` mode). |
| `hierograph.graph.watch.mode` | `polling` | `off` / `polling` / `native` (`manifest` mode). |
| `hierograph.graph.watch.interval` | `1000` | Poll interval, ms. |
| `hierograph.graph.watch.settle` | `400` | Quiet period, ms, before rebuilding after a change. |
| `hierograph.graph.dump.enabled` | `false` | Write a one-line-per-node text dump after each build. |
| `hierograph.graph.dumpFile` | `hierarchical-graph.txt` | Where the dump is written. |

In `manifest` mode the detail snapshot is written to a `snapshot/` directory beside
the manifest (one `gen-<nnnn>` subdirectory per build; superseded ones are pruned).

---

## 11. Working with the service

**Writing a tool.** Depend on the service and read from the served interface
(§9). Guard every entry point with `isGraphAvailable`. For member-level detail,
take `readyDetailDir()`; if it is `null`, return a "detail rebuilding, retry"
status rather than an empty or stale result, and union the Parquet rows with
`derivedDetailRefs(…)` for the same scope. Build pagination cursors on `dataHash`
and treat a changed hash as cursor invalidation. Never cache a snapshot-derived
value across calls — read it fresh, so a concurrent reload is picked up.

**Operating a server.** Pick the source with `hierograph.graph.source`. For
`parquet`, point `…parquetDir` at a snapshot directory and call the `reload_graph`
tool after the snapshot is rewritten. For `manifest`, point `…manifest` at the
build manifest; the graph loads live and, with watching on, follows the code with
no restart — set `…include-tests` to add test artifacts, and tune
`…watch.interval` / `…watch.settle` to taste. Enable the dump only for debugging.

**Testing.** Construct the service without Spring and call `seed(model)` to install
a ready-made model directly, bypassing any source, watcher or Parquet I/O.
