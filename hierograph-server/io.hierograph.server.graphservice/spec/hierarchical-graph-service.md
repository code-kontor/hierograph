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
live snapshot, it builds a new one and replaces the old. A **view's** snapshot is
derived from a base snapshot by `ViewService`: the same snapshot with the view's
model (a fork with group nodes), a search index that also finds the groups, a data
hash that also covers the view document, and `view` set to the view's name (views
specification, §7).

**Atomic swap.** The current snapshot lives behind a single atomic reference.
Every public read resolves it once, so concurrent tool calls each see one coherent
(model, index, hash) set. A rebuild publishes by replacing that reference in a
single step — there is no window in which a reader sees a partial graph.

**Pinned snapshot.** A tool call reads the service many times: the model, then the
data hash for its cursor, then the detail directory. `withPinnedSnapshot { … }` pins
the snapshot that is live when the block starts to the calling thread, and every read
inside the block resolves that snapshot, even when a reload swaps in a new one
meanwhile. The MCP server runs every tool call inside such a block, so one answer is
always computed from one graph. While a pinned snapshot is not the live one,
`readyDetailDir()` returns `null`, so a call never pairs an old model with the new
build's detail data. Pins are per thread, and a nested block keeps the outer pin.

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
reload that changes the graph (their data hash does not match); a reload of
unchanged code keeps the hash and the cursors.

**Shutdown** stops the watcher and the background executors.

---

## 6. Live updates (manifest mode)

When watching is enabled, the service tracks the manifest file and every output
directory the manifest declares, including directories that do not exist yet. An
output may have several directories (Java and Kotlin classes of one module); a
change in any of them reports the output. The service
applies changes on a dedicated single-thread updater (kept off the watcher's own
event thread):

- **A module's output changed** → rescan only the changed modules, rebuild the
  graph from the cached union of per-module scans, and swap it in. Unchanged
  modules are not re-scanned. An output whose directory appears (a module
  compiled for the first time) is scanned and added; one whose directories have
  all disappeared drops the module's classes.
- **The manifest itself changed** (module set or classpath) → apply it as a diff
  against the cached scans, rebuild, and re-target the watcher at the changed module
  set. The diff is keyed by output (`<gav>`, or `<gav>:test`):

  | Output | Effect |
  |--------|--------|
  | removed | its scan and classpath are dropped |
  | added | declared; scanned if one of its directories exists |
  | output directories changed, or contents changed since its scan | rescanned |
  | otherwise (including a classpath or name change) | the cached scan is reused |

  A classpath change alone needs no rescan: a scan depends only on the output's
  key and directories, and classpaths are applied when the graph is built. A
  renamed module is a removal plus an addition. Each scan records a signature of
  its directories, a fold of `(path, size, mtime)` over their `.class` files,
  taken just before the scan. The contents check compares it with the
  directories' current signature. This check is needed because the watcher re-baselines when
  it is re-targeted: classes the same build compiled before that point are never
  reported as a change. An explicit `reload` still rescans every module.

Watch mode is `polling` (default), `native`, or `off`, with configurable poll
interval and a settle delay that coalesces a burst of edits into one rebuild. The
polling watcher sees a directory appear as a signature change. A native watch can
only be registered on an existing directory, so the native watcher checks on each
tick which declared directories exist, reports the ones that appeared or
disappeared, and re-registers. An incremental update that fails logs and keeps
the previous graph.

### Build files

The manifest lists the build's build files (for Maven, POMs and `.mvn`
configuration) with the hashes of the content it was merged from (see the
[build manifest specification](hierograph-build-manifest.md)). Both watchers poll
these files by `(size, mtime)` on their regular tick: there are tens to hundreds
of them, polling is cheap and works the same across a mounted volume, and native
events on module directories would also deliver every source-file save. Changes
are collected until no build file has changed for the settle delay and reported
once, because a rename or a version bump touches many build files at once.

On a report, and whenever a manifest is applied, `BuildFileMonitor` compares the
hashes. A build file whose content differs from its recorded hash, or that no
longer exists, is **stale**: the module set or a classpath may have changed
without the manifest knowing. `hierograph.graph.watch.build-files` selects the
reaction:

| Mode | Reaction |
|------|----------|
| `off` | build files are not watched |
| `notify` (default) | the stale build files are reported in `build_state` until a build rewrites the manifest |
| `regenerate` | the service runs the regeneration command in the build root; the rewritten manifest then arrives as a manifest change |

`notify` is the default because running a build is a side effect the developer
opts into.

**Regeneration.** The service knows no build tool; it runs the command the
manifest's producer names. The command is chosen in this order:

| Source | Used when |
|--------|-----------|
| `hierograph.graph.regenerate.command`, split on whitespace | set: an override for setups the producer cannot anticipate |
| the manifest's `regenerate` | present; `./`-relative arguments resolve against the local build root |
| none | `regenerate` behaves as `notify`, with a warning naming the missing command |

The command runs in the build root, and its output goes to `regenerate.log` in the
state directory. The Maven plugin names `./mvnw` or `mvn` with `-q -B`, the active
profiles and the goal that wrote the manifest (see the
[build manifest specification](hierograph-build-manifest.md)); another producer
names what fits its tool.

**A missing manifest is generated first.** When the manifest does not exist at
startup — or at a `reload` — and the mode is `regenerate` with a configured
command, the service runs that command synchronously, then loads the result. This
is how a container starts from nothing. A failed run is reported as
`regeneration-failed`; the service then starts without a graph.

| Rule | Why |
|------|-----|
| One run at a time; a request during a run schedules exactly one follow-up run | the running build may have read the build files before the edit |
| A run exceeding `hierograph.graph.regenerate.timeout` is killed | a hanging build must not block later runs |
| A failed run reports the last lines of its output | the developer sees why the graph did not follow; the previous graph stays live |
| A relocated manifest (read under a different build root) is never regenerated | the server runs in a container without the build host's tools; it behaves as `notify` |

Applying a manifest re-evaluates the state and starts a regeneration if build
files are still stale, so `reload_graph` also retries a failed regeneration.

**Limits.** Changes outside the listed build files are not detected: for Maven, a
profile activated through the environment or `settings.xml`, and a SNAPSHOT
dependency updated in the local repository. A build followed by `reload_graph` covers them.
A regeneration that needs a dependency not yet in the local repository fails when
the command runs offline.

**`build_state`.** In `manifest` mode, `reload_graph` and `graph_overview` return
the state as a map with `status`, `changed_files`, `build_tool` (the manifest's
`buildTool` name, when present) and, after a failure, `message` (the command that
ran and the end of its output):

| Status | Meaning |
|--------|---------|
| `current` | every build file matches the manifest |
| `stale` | the listed build files changed since the manifest was written |
| `regenerating` | a regeneration is running |
| `regeneration-failed` | the last regeneration failed; `message` holds the end of its output |

**Starting without output.** When the manifest is readable but no declared output
exists, or the graph cannot be built, the service starts without a graph and
still starts the watcher; the first output that appears builds the graph. A
failed `reload` or manifest reload also re-targets the watcher, so the service
follows a changed module set even before any of it is compiled.

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

What tools read from the service (all reads resolve the current snapshot atomically, or
the pinned one inside `withPinnedSnapshot`):

| Member | Purpose |
|--------|---------|
| `isGraphAvailable` | whether any graph is loaded; tools check this before answering |
| `withPinnedSnapshot { … }` | run a block against one snapshot, whatever reloads happen meanwhile |
| `liveSnapshot()` / `withPinnedSnapshot(snapshot) { … }` | capture the live snapshot once and pin it later, for work that spans callbacks or threads (the GraphQL API pins each request this way) |
| `currentSnapshot()` | the snapshot this thread reads: the pinned one, else the live one |
| `onSnapshotPublished(listener)` | called after each new snapshot becomes live, for caches derived from a snapshot (the view cache drops the views of the replaced one) |
| `stateDirectory` / `buildRootDirectory` | the resolved state directory and build root; the defaults of the view directories derive from them |
| `model` | the hierarchical model (navigation, hierarchy, dependencies) |
| `searchIndex` | the node search index for the current model |
| `dataHash` | fingerprint of the current model; the basis for pagination-cursor validity |
| `graphStore` | the type-level store backing the snapshot, when present |
| `memberHandleIndex` / `memberHandle(key)` | map member node handles ↔ member keys for the detail tools |
| `readyDetailDir()` | the Parquet detail directory, or `null` while rebuilding (§7) |
| `derivedDetailRefs(from, to, relationship)` | load-derived cross-artifact edges for a scope (§8) |
| `parquetDirectory` | directory the node-property provider reads (may lag during a rescan) |
| `reload()` | rebuild and swap; returns a status map, with `build_state` in `manifest` mode |
| `buildState()` | the `build_state` map (§6), or `null` outside `manifest` mode |
| `seed(model)` | install a ready-made model directly — for tests, bypassing any source |

---

## 10. Configuration

All properties are optional; defaults suit a local server.

| Property | Default | Meaning |
|----------|---------|---------|
| `hierograph.graph.source` | `parquet` | `parquet` or `manifest` (§4). |
| `hierograph.graph.parquetDir` | `parquet` | Snapshot, `parquet` mode: a snapshot directory, or a single `.hierograph` archive or `collect` container. |
| `hierograph.graph.manifest` | `.hierograph/manifest.json` | Build manifest, `manifest` mode. |
| `hierograph.graph.stateDir` | *(the manifest's directory)* | Where the snapshot and the regeneration log are written. |
| `hierograph.graph.buildRoot` | *(the manifest's grandparent)* | Local build root: path re-rooting and the regeneration working directory. |
| `hierograph.graph.include-tests` | `false` | Also scan each module's test output (`manifest` mode). |
| `hierograph.graph.watch.mode` | `polling` | `off` / `polling` / `native` (`manifest` mode). |
| `hierograph.graph.watch.interval` | `1000` | Poll interval, ms. |
| `hierograph.graph.watch.settle` | `400` | Quiet period, ms, before rebuilding after a change. |
| `hierograph.graph.watch.build-files` | `notify` | `off` / `notify` / `regenerate`: reaction to edited build files (§6). |
| `hierograph.graph.regenerate.command` | *(empty)* | Regeneration command override; empty uses the manifest's `regenerate` command. |
| `hierograph.graph.regenerate.timeout` | `600000` | Regeneration timeout, ms. |
| `hierograph.graph.dump.enabled` | `false` | Write a one-line-per-node text dump after each build. |
| `hierograph.graph.dumpFile` | `hierarchical-graph.txt` | Where the dump is written. |
| `hierograph.views.projectDir` | *(`<build root>/.hierograph/views`)* | Project view documents, read-only (views specification, §7.1). |
| `hierograph.views.stateDir` | *(`<state dir>/runtime-views`)* | Views defined at runtime (views specification, §7.1). |

In `manifest` mode the detail snapshot is written to a `snapshot/` directory in the
state directory (one `gen-<nnnn>` subdirectory per build; superseded ones are
pruned). A generation holds one **exploded** artifact per scanned artifact — a
`<artifact>.hierograph/` directory of tables, not an archive — so pruning it removes
everything it holds. The service writes nothing outside the state directory, and
reads snapshots in place without extracting them.

**State directory and build root.** By default both follow from the manifest's
location: the state directory is the manifest's directory, the build root its
parent (`<build root>/.hierograph/manifest.json`). A container sets both, so the
manifest and every write live in its state directory while the build root stays
read-only — and because the manifest it generates records that build root, it is
not relocated and may be regenerated.

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
