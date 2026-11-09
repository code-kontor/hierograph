/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.server.graphservice

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.PlaceholderPolicy
import io.hierograph.server.mapping.GraphStoreToHGModel
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import io.hierograph.server.graphservice.pagination.DataHash
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.measureTimedValue

@Service
class HierarchicalGraphService {

    companion object {
        private val log = LoggerFactory.getLogger(HierarchicalGraphService::class.java)
    }

    @Value("\${hierograph.graph.dump.enabled:false}")
    private var graphDumpEnabled: Boolean = false

    /** `manifest` mode only: also scan each module's test output (as a separate `<gav>:test` artifact). */
    @Value("\${hierograph.graph.include-tests:false}")
    private var includeTests: Boolean = false

    @Value("\${hierograph.graph.dumpFile:hierarchical-graph.txt}")
    private lateinit var graphDumpFile: String

    /**
     * Where the graph's facts come from:
     * - `parquet` (default): load a pre-built Parquet snapshot directory ([parquetDir]) — the CI / push flow.
     * - `manifest`: scan the modules named in a build manifest ([manifestFile]) on the fly and
     *   write the Parquet snapshot into `<manifest dir>/snapshot`, then load that — the local-dev flow.
     */
    @Value("\${hierograph.graph.source:parquet}")
    private lateinit var graphSource: String

    /** Directory holding the Parquet snapshot tables the graph is built from; `parquet` mode. */
    @Value("\${hierograph.graph.parquetDir:parquet}")
    private lateinit var parquetDir: String

    /** Build manifest the server scans in `manifest` mode (written by the Hierograph Maven plugin). */
    @Value("\${hierograph.graph.manifest:.hierograph/manifest.json}")
    private lateinit var manifestFile: String

    /**
     * Where the server writes in `manifest` mode — the Parquet snapshot and the regeneration log. Empty: the
     * manifest's directory. A container sets it to its state directory, outside the read-only workspace.
     */
    @Value("\${hierograph.graph.stateDir:}")
    private var stateDirSetting: String = ""

    /**
     * The local build root: the target of path re-rooting and the working directory of regeneration. Empty: the
     * manifest's grandparent (`<build root>/.hierograph/manifest.json`). Set explicitly when the manifest lives
     * elsewhere, e.g. in a container's state directory; a manifest recorded under this root is then not relocated.
     */
    @Value("\${hierograph.graph.buildRoot:}")
    private var buildRootSetting: String = ""

    /** Watch mode (`manifest` mode only): `off` | `polling` | `native`. `native` falls back to polling for now. */
    @Value("\${hierograph.graph.watch.mode:polling}")
    private lateinit var watchMode: String

    @Value("\${hierograph.graph.watch.interval:1000}")
    private var watchIntervalMs: Long = 1000

    @Value("\${hierograph.graph.watch.settle:400}")
    private var watchSettleMs: Long = 400

    /** Reaction to edited build files (`manifest` mode, watching on): `off` | `notify` | `regenerate`. */
    @Value("\${hierograph.graph.watch.build-files:notify}")
    private var buildFilesMode: String = "notify"

    /** Regeneration command override (whitespace-separated); empty uses the command the manifest names. */
    @Value("\${hierograph.graph.regenerate.command:}")
    private var regenerateCommand: String = ""

    /** Regeneration timeout, ms; a run that takes longer is killed. */
    @Value("\${hierograph.graph.regenerate.timeout:600000}")
    private var regenerateTimeoutMs: Long = 600_000

    /**
     * Whether the manifest still reflects the build's build files, and the regeneration runner. Created on
     * first use, after the configuration is injected.
     */
    private val buildFileMonitor by lazy {
        BuildFileMonitor(
            mode = BuildFileMonitor.Mode.parse(buildFilesMode),
            commandOverride = regenerateCommand.trim().takeIf { it.isNotEmpty() }?.split(Regex("\\s+")),
            timeoutMs = regenerateTimeoutMs,
            stateDir = stateDir(),
            inspect = {
                currentScanned?.let { scanned ->
                    BuildFileMonitor.Inspection(
                        staleFiles = manifestGraphSource.staleBuildFiles(scanned),
                        buildRoot = buildRoot(),
                        relocated = scanned.relocated,
                        buildTool = scanned.buildTool,
                        command = scanned.regenerate,
                    )
                }
            },
        )
    }

    /**
     * The `build_state` reported by `reload_graph` and `graph_overview`: whether the manifest reflects the
     * build's build files (`current`), a build file changed since (`stale`, with the changed files), or a
     * regeneration is running or failed. `null` outside `manifest` mode.
     */
    fun buildState(): Map<String, Any?>? = if (manifestMode) buildFileMonitor.state.toMap() else null

    private val manifestGraphSource = ManifestGraphSource()

    /** The cached per-module scans backing the live `manifest`-mode graph; incremental updates rescan into it. */
    @Volatile
    private var currentScanned: ManifestGraphSource.Scanned? = null

    /** The running file watcher, if any. */
    @Volatile
    private var watcher: GraphWatcher? = null

    /** Single thread that applies watch-triggered updates, kept off the watcher's own poll/event thread. */
    private val updateExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hierograph-graph-updater").apply { isDaemon = true }
    }

    /**
     * True when [graphSource] selects the manifest (server-side scan) flow. A service built without Spring
     * and filled by [seed] has no source configured and is not in manifest mode.
     */
    private val manifestMode: Boolean
        get() = ::graphSource.isInitialized && graphSource.equals("manifest", ignoreCase = true)

    /**
     * Base directory for the Parquet snapshot the detail tools read: [parquetDir] in `parquet` mode, or
     * the `snapshot` directory in the state directory in `manifest` mode (async writes land in zero-padded
     * `gen-<nnnn>` subdirectories of it).
     */
    private fun snapshotDir(): Path =
        if (manifestMode) stateDir().resolve("snapshot")
        else Path.of(parquetDir)

    private fun manifestPath(): Path = Path.of(manifestFile).toAbsolutePath().normalize()

    /** [stateDirSetting], or the manifest's directory. */
    private fun stateDir(): Path =
        stateDirSetting.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() } ?: manifestPath().parent

    /** The explicitly configured build root, or `null` to derive it from the manifest's location. */
    private fun configuredBuildRoot(): Path? =
        buildRootSetting.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }

    private fun buildRoot(): Path = configuredBuildRoot() ?: ManifestGraphSource.defaultBuildRoot(manifestPath()) ?: manifestPath().parent

    /**
     * In `manifest` mode without a manifest yet, generate it with the configured regeneration command before
     * loading (a container's first start). A no-op when a manifest exists or no command is configured.
     */
    private fun generateManifestIfMissing() {
        if (!manifestMode || Files.exists(manifestPath())) return
        buildFileMonitor.generateMissingManifest(buildRoot())
    }

    /**
     * The Parquet detail source, coupled to the graph [generation][GraphSnapshot.generation] it was built
     * for. In `parquet` mode it is set synchronously with the snapshot (always current). In `manifest`
     * mode the live graph comes from a direct in-memory ingest and this is published **asynchronously**
     * once the Parquet write for that generation finishes — until then its generation lags the live
     * snapshot, and [readyDetailDir] / [derivedDetailRefs] withhold it so no *stale* detail is served.
     */
    private data class DetailSource(val generation: Long, val dir: Path?, val derived: List<DetailRef>)

    private val detailSource = AtomicReference(DetailSource(generation = -1, dir = null, derived = emptyList()))

    /** Monotonic build id; each [buildSnapshot] takes the next value and stamps its snapshot + detail source. */
    private val graphGen = AtomicLong(0)

    /** Single background thread that writes the Parquet snapshot off the hot path in `manifest` mode. */
    private val detailWriter = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hierograph-snapshot-writer").apply { isDaemon = true }
    }

    /**
     * The Parquet directory the detail tools may read, or `null` when the detail snapshot for the live
     * graph is not ready yet (still being written after a `manifest`-mode rescan, or the write failed).
     * Detail tools must treat `null` as "rebuilding", never fall back to a previous directory.
     */
    fun readyDetailDir(): Path? {
        val detail = detailSource.get()
        val live = live()?.generation
        return if (detail.dir != null && live != null && detail.generation == live) detail.dir else null
    }

    /** Directory the Parquet node-property provider reads; may lag the live graph during a rescan. */
    val parquetDirectory: String get() = (detailSource.get().dir ?: snapshotDir()).toString()

    /** The type-level GraphStore backing the current snapshot in `parquet` mode, else `null`. */
    val graphStore: GraphStore? get() = live()?.graphStore

    /** Reverse index `node handle → member key` for the Parquet detail tools. */
    val memberHandleIndex: Map<Long, String> get() = live()?.memberHandles ?: emptyMap()

    /**
     * The node handle for a bare member key (`<owner fqn>#<signature>`, as detail refs carry it): the
     * artifact-scoped member node's handle when the member is in the graph, else a stable but
     * non-navigable handle of the bare key (e.g. a member of an external type).
     */
    fun memberHandle(memberKey: String): Long =
        graphStore?.memberByKey(memberKey)?.key?.let { NodeHandle.of(it) } ?: NodeHandle.of(memberKey)

    /**
     * The currently-served graph snapshot. Swapped atomically by [reload]; every read below goes
     * through it, so concurrent tool calls always see one consistent (model, index, hash) set.
     */
    private val snapshotRef = AtomicReference<GraphSnapshot>()

    /** The snapshot pinned to the current thread by [withPinnedSnapshot], if any. */
    private val pinned = ThreadLocal<GraphSnapshot?>()

    /** The snapshot this thread reads: the pinned one inside [withPinnedSnapshot], else the live one. */
    private fun live(): GraphSnapshot? = pinned.get() ?: snapshotRef.get()

    /**
     * Runs [block] with the live snapshot pinned to the current thread: every read of [model],
     * [searchIndex], [dataHash], [graphStore], [memberHandleIndex] and the detail source inside it sees
     * that one snapshot, even when a reload swaps in a new one meanwhile. A tool call that reads the
     * service several times therefore never mixes two graphs; it answers from the graph that was live
     * when it started. While the pinned snapshot is no longer live, [readyDetailDir] reports the detail
     * data as not ready, so the call never pairs an old model with new detail data.
     *
     * Nested calls keep the outer pin. Without a loaded graph, [block] runs unpinned.
     */
    fun <T> withPinnedSnapshot(block: () -> T): T {
        if (pinned.get() != null) return block()
        val snapshot = snapshotRef.get() ?: return block()
        pinned.set(snapshot)
        try {
            return block()
        } finally {
            pinned.remove()
        }
    }

    val model: HGModel get() = current().model

    val searchIndex: NodeSearchIndex get() = current().searchIndex

    /** Fingerprint of the currently-loaded snapshot; changes on every [reload] that changes the graph. */
    val dataHash: String get() = current().dataHash

    /**
     * Whether a hierarchical graph is currently loaded. `false` when the graph source could not be
     * built (see [init]); tools consult this to decide whether they can answer at all.
     */
    val isGraphAvailable: Boolean get() = snapshotRef.get() != null

    private fun current(): GraphSnapshot =
        live() ?: error("Hierarchical graph has not been loaded yet")

    @PostConstruct
    fun init() {
        // Start "empty" if the graph cannot be built: build best-effort, but never fail startup. Tools
        // then report that no graph is available until one is loaded (e.g. via reload_graph). A manifest
        // that was read is still watched, so the graph builds itself once compiled output appears.
        try {
            generateManifestIfMissing()
            val built = loadSnapshot()
            snapshotRef.set(built.snapshot)
            built.publishDetail()
            startWatchingIfEnabled()
        } catch (e: Exception) {
            log.warn(
                "No hierarchical graph loaded at startup: {}. The server is starting empty; tools will " +
                        "report that no graph is available until one is loaded (e.g. via reload_graph).",
                e.message,
            )
            startWatchingIfEnabled()
        }
    }

    @PreDestroy
    fun shutdown() {
        stopWatcher()
        if (manifestMode) buildFileMonitor.close()
        updateExecutor.shutdownNow()
        detailWriter.shutdownNow()
    }

    /**
     * Rebuild the graph from the Parquet snapshot and swap it in atomically, without restarting the
     * server. Call this after a re-scan rewrites the snapshot so the tools reflect the current code.
     *
     * The new snapshot is built fully before the swap, so on failure the previously loaded graph keeps
     * being served and an `error` status is returned rather than leaving the server empty. Note that
     * pagination cursors issued against the previous snapshot become stale (their data hash no longer
     * matches) and must be re-requested.
     */
    fun reload(): Map<String, Any?> {
        log.info("Reloading hierarchical graph ({} source): {}", graphSource, if (manifestMode) manifestFile else parquetDir)
        return try {
            generateManifestIfMissing()
            val (built, elapsed) = measureTimedValue { loadSnapshot() }
            snapshotRef.set(built.snapshot)
            built.publishDetail()
            startWatchingIfEnabled()
            val snapshot = built.snapshot
            val rootChildren = snapshot.model.hierarchy.let { it.childrenOf(it.rootNode).size }
            log.info("Reload complete: {} root children, hash {} in {}.", rootChildren, snapshot.dataHash, elapsed)
            linkedMapOf<String, Any?>(
                "status" to "reloaded",
                "root_children" to rootChildren,
                "search_index_entries" to snapshot.searchIndex.entries.size,
                "data_hash" to snapshot.dataHash,
                "reload_millis" to elapsed.inWholeMilliseconds
            ).apply { buildState()?.let { put("build_state", it) } }
        } catch (e: Exception) {
            log.error("Reload failed; keeping the previously loaded graph.", e)
            startWatchingIfEnabled()
            linkedMapOf<String, Any?>(
                "status" to "error",
                "message" to (e.message ?: e.javaClass.simpleName),
                "data_hash" to snapshotRef.get()?.dataHash,
                "note" to "The previously loaded graph is still being served; re-run the scan and retry."
            ).apply { buildState()?.let { put("build_state", it) } }
        }
    }

    /**
     * Seed the service with a ready-made model without reading a graph source. Builds the derived index
     * and data hash and installs them as the current snapshot. Intended for non-Spring construction
     * in tests; production code loads via [init] / [reload].
     */
    fun seed(model: HGModel) {
        snapshotRef.set(buildSnapshotFrom(model, graphStore = null, memberHandles = emptyMap(), generation = graphGen.incrementAndGet()))
    }

    /** Build a fresh snapshot, logging progress (and the debug dump if enabled). Detail is published by the caller. */
    private fun loadSnapshot(): Built {
        log.info("Creating hierarchical graph ({} source)...", graphSource)
        val (built, elapsed) = measureTimedValue {
            buildSnapshot()
        }
        val snapshot = built.snapshot

        val hierarchy = snapshot.model.hierarchy
        val children = hierarchy.childrenOf(hierarchy.rootNode)
        log.info("Hierarchical graph created with {} root children in {}.", children.size, elapsed)
        log.info("Search index built with {} entries.", snapshot.searchIndex.entries.size)
        log.info("Captured data-snapshot hash: {}", snapshot.dataHash)

        // Count nodes by kind (skip the root node itself)
        val kindCounts = mutableMapOf<Any?, Int>()
        for (child in children) {
            hierarchy.traverse(child) { node ->
                kindCounts.merge(node.kind, 1) { a, b -> a + b }
            }
        }
        kindCounts.entries
            .sortedByDescending { it.value }
            .forEach { (kind, count) -> log.info("  {}: {}", kind, count) }
        log.info("Total nodes: {}", kindCounts.values.sum())

        // Optional debug dump: one indented line per node, with name/fqn. Off by default.
        if (graphDumpEnabled) {
            val outputFile = File(graphDumpFile)
            outputFile.bufferedWriter().use { writer ->
                TreeTraverser.dumpTree(hierarchy.rootNode, hierarchy, sink = { writer.appendLine(it) })
            }
            log.info("Hierarchical graph written to: {}", outputFile.absolutePath)
        }

        return built
    }

    /**
     * A freshly built snapshot plus the action that publishes its detail source. The caller swaps
     * [snapshot] in first, then runs [publishDetail] — so the detail source's generation only becomes
     * current once the live snapshot already is, never before.
     */
    private class Built(val snapshot: GraphSnapshot, val publishDetail: () -> Unit)

    /** Dispatches to the configured source: a synchronous Parquet load, or a `manifest`-mode scan. */
    private fun buildSnapshot(): Built = if (manifestMode) buildFromManifest() else buildFromParquet()

    /**
     * `parquet` mode: read the Parquet tables into a [GraphStore] and bridge to an [HGModel]. Detail is
     * published synchronously (post-swap) with the same generation, so it is ready at once.
     */
    private fun buildFromParquet(): Built {
        val dir = snapshotDir()
        val store = GraphStore(PlaceholderPolicy.EXCLUDE_UBIQUITOUS)
        val reader = ParquetReader()
        reader.load(dir, store)
        val generation = graphGen.incrementAndGet()
        val derived = deriveDetailRefs(dir, store, reader)
        val snapshot = buildSnapshotFrom(GraphStoreToHGModel.build(store), store, memberHandles(store), generation)
        return Built(snapshot) { detailSource.set(DetailSource(generation, dir, derived)) }
    }

    /**
     * `manifest` mode: scan the build's modules and ingest the result **straight into the GraphStore**, so
     * the hierarchical graph is live without waiting on Parquet I/O. The Parquet snapshot the detail tools
     * read is written **asynchronously** (see [scheduleDetailWrite]); until the write for this generation
     * lands, [readyDetailDir] withholds detail rather than serving the previous scan's.
     */
    private fun buildFromManifest(): Built {
        val scanned = manifestGraphSource.scan(manifestPath(), includeTests, configuredBuildRoot())
        currentScanned = scanned
        requireCompiled(scanned)
        return buildManifestSnapshot(scanned)
    }

    /** A graph needs at least one scanned module; with none, the caller keeps watching for the first output. */
    private fun requireCompiled(scanned: ManifestGraphSource.Scanned) = check(scanned.modules.isNotEmpty()) {
        "No module of manifest $manifestFile is compiled yet; watching ${scanned.declared.size} declared output(s)"
    }

    /** Build the nav snapshot from a (cached) set of per-module scans; detail is published post-swap. */
    private fun buildManifestSnapshot(scanned: ManifestGraphSource.Scanned): Built {
        val store = GraphStore(PlaceholderPolicy.EXCLUDE_UBIQUITOUS)
        manifestGraphSource.ingest(scanned, store)
        val model = GraphStoreToHGModel.build(store)
        val generation = graphGen.incrementAndGet()
        val snapshot = buildSnapshotFrom(model, store, memberHandles(store), generation)
        return Built(snapshot) { scheduleDetailWrite(scanned, store, generation) }
    }

    // ---- File watch & incremental updates (manifest mode) --------------------------------------------

    /**
     * Start (or restart) watching the current module set, if `manifest` mode and watching is enabled. Also
     * re-evaluates the build state against the (re)applied manifest, which may start a regeneration.
     */
    private fun startWatchingIfEnabled() {
        if (!manifestMode) return
        val mode = watchMode.lowercase()
        if (mode == "off") return
        val scanned = currentScanned ?: return
        stopWatcher()
        buildFileMonitor.refresh()
        val w = when (mode) {
            "native" -> NativeGraphWatcher(watchSettleMs)
            else -> PollingGraphWatcher(watchIntervalMs, watchSettleMs)
        }
        w.start(
            WatchTargets(
                manifestPath(),
                scanned.moduleDirs,
                if (buildFileMonitor.watchesBuildFiles) scanned.buildFiles.keys else emptySet(),
            ),
            onChange = { gavs -> updateExecutor.submit { applyIncremental(gavs) } },
            onManifestChange = { updateExecutor.submit { reloadFromManifest() } },
            onBuildFilesChange = { updateExecutor.submit { buildFileMonitor.refresh() } },
        )
        watcher = w
        log.info(
            "Watching {} module(s) and {} build file(s) for changes (mode={}, build files={}, interval={}ms, settle={}ms)",
            scanned.moduleDirs.size, scanned.buildFiles.size, mode, buildFilesMode, watchIntervalMs, watchSettleMs,
        )
    }

    private fun stopWatcher() {
        watcher?.let { runCatching { it.close() } }
        watcher = null
    }

    /** Rescan only the changed modules, rebuild the graph from the cached union, and swap it in. */
    private fun applyIncremental(changedGavs: Set<String>) {
        val previous = currentScanned ?: return
        try {
            val (built, elapsed) = measureTimedValue {
                val rescanned = manifestGraphSource.rescan(previous, changedGavs)
                currentScanned = rescanned
                buildManifestSnapshot(rescanned)
            }
            snapshotRef.set(built.snapshot)
            built.publishDetail()
            val rootChildren = built.snapshot.model.hierarchy.let { it.childrenOf(it.rootNode).size }
            log.info(
                "Incremental update for {} module(s) in {}: {} root children, hash {}",
                changedGavs.size, elapsed, rootChildren, built.snapshot.dataHash,
            )
        } catch (e: Exception) {
            log.error("Incremental update failed for {}; keeping the previous graph.", changedGavs, e)
        }
    }

    /**
     * The manifest file itself changed (module set / classpath): apply it as a diff against the cached scans
     * ([ManifestGraphSource.reload]), so only added or changed modules are scanned, and re-target the watcher.
     */
    private fun reloadFromManifest() {
        try {
            log.info("Manifest changed; applying it and re-targeting the watcher")
            val previous = currentScanned
            val built = if (previous == null) {
                loadSnapshot() // nothing cached yet: a full scan, which also refreshes currentScanned
            } else {
                val (built, elapsed) = measureTimedValue {
                    val scanned = manifestGraphSource.reload(previous, manifestPath(), includeTests, configuredBuildRoot())
                    currentScanned = scanned
                    requireCompiled(scanned)
                    buildManifestSnapshot(scanned)
                }
                log.info("Manifest applied in {}: hash {}", elapsed, built.snapshot.dataHash)
                built
            }
            snapshotRef.set(built.snapshot)
            built.publishDetail()
            startWatchingIfEnabled()
        } catch (e: Exception) {
            log.error("Manifest reload failed; keeping the previous graph.", e)
            startWatchingIfEnabled() // follow the new module set even when nothing in it is compiled yet
        }
    }

    /**
     * Write the Parquet snapshot for [generation] on the background writer, then publish it as the detail
     * source — but only if no newer build has started meanwhile (otherwise the work is discarded). Reuses
     * the live [store] (immutable after build) for the hierarchy-derived edges; reads the just-written
     * members/calls back from Parquet, matching the `parquet`-mode path exactly.
     */
    private fun scheduleDetailWrite(scanned: ManifestGraphSource.Scanned, store: GraphStore, generation: Long) {
        detailWriter.submit {
            val base = snapshotDir()
            val genDir = base.resolve("gen-%04d".format(generation))
            try {
                val elapsed = kotlin.time.measureTime {
                    manifestGraphSource.writeSnapshot(scanned, genDir)
                }
                val derived = deriveDetailRefs(genDir, store, ParquetReader())
                if (graphGen.get() == generation) {
                    detailSource.set(DetailSource(generation, genDir, derived))
                    pruneSnapshotDir(base, keep = genDir)
                    log.info("Detail snapshot ready (generation {}) at {} ({} write)", generation, genDir, elapsed)
                } else {
                    deleteRecursively(genDir) // a newer rescan superseded this one
                    log.info("Discarded superseded detail snapshot (generation {})", generation)
                }
            } catch (e: Exception) {
                log.error("Async detail-snapshot write failed (generation {}); detail stays unavailable until the next reload.", generation, e)
            }
        }
    }

    /** Reverse index for the detail tools: member node handle -> bare member key (the snapshot key). */
    private fun memberHandles(store: GraphStore): Map<Long, String> =
        store.nodes().asSequence()
            .filter { it.kind in JavaKinds.MEMBER_KINDS }
            .associateBy({ NodeHandle.of(it.key) }, { it.fqn })

    /**
     * Keep only [keep] under [base], deleting every other entry — superseded `gen-*` directories and any
     * stray files left in the snapshot root (e.g. archives written there by an earlier implementation).
     * Best-effort: the live detail source already points at [keep], and nothing served reads the rest.
     */
    private fun pruneSnapshotDir(base: Path, keep: Path) {
        if (!Files.isDirectory(base)) return
        runCatching {
            Files.list(base).use { entries ->
                entries.filter { it != keep }.forEach { runCatching { deleteRecursively(it) } }
            }
        }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /**
     * Hierarchy-dependent member edges (overrides / virtual + resolved calls) computed over the full
     * assembled graph — the load-time home of what the scanner cannot derive across independently-scanned
     * artifacts. Keeps only the edges not already in the snapshot's `detail_refs`, so a single-shot
     * snapshot (already complete) contributes nothing and a per-artifact snapshot gains its cross-artifact
     * edges. See [HierarchyResolution].
     */
    private fun deriveDetailRefs(dir: Path, store: GraphStore, reader: ParquetReader): List<DetailRef> {
        val methodsByOwner = reader.readAllMembers(dir)
            .filter { it.kind == JavaNodeKind.METHOD || it.kind == JavaNodeKind.CONSTRUCTOR }
            .groupBy { it.key.substringBefore('#') }
        val superByFqn = store.typeDependencies()
            .filter { it.extends || it.implements }
            .groupBy({ it.from }, { it.to })
        val types = store.nodes().asSequence()
            .filter { JavaKinds.isTypeKind(it.kind) }
            .map { n ->
                HierarchyResolution.Type(
                    fqn = n.fqn,
                    directSupertypes = superByFqn[n.fqn].orEmpty(),
                    methods = methodsByOwner[n.fqn].orEmpty().map { HierarchyResolution.Method(it.key, it.signature, it.modifiers, it.lineFrom, it.bridgeTarget) },
                    external = n.kind == JavaKinds.EXTERNAL_TYPE,
                )
            }.toList()
        val directCalls = reader.readDetailRefs(dir, relationship = "calls")
        val resolved = HierarchyResolution.resolve(types, directCalls)

        val existing = reader.readDetailRefs(dir).mapTo(HashSet()) { Triple(it.fromKey, it.toKey, it.relationship) }
        return resolved.filterNot { Triple(it.fromKey, it.toKey, it.relationship) in existing }
    }

    /**
     * The load-derived member edges matching the given scope (same semantics as
     * [ParquetReader.readDetailRefs]): `from_type` in [fromTypes], `to_type` in [toTypes], `relationship`
     * equal to [relationship] — each ignored when null/empty. The detail tools union this with the
     * Parquet `detail_refs`.
     */
    fun derivedDetailRefs(fromTypes: Collection<String>? = null, toTypes: Collection<String>? = null, relationship: String? = null): List<DetailRef> {
        // Withhold when the detail source does not match the live graph (same guard as readyDetailDir),
        // so a rescan in flight never surfaces the previous scan's derived edges.
        if (readyDetailDir() == null) return emptyList()
        val from = fromTypes?.takeIf { it.isNotEmpty() }?.toHashSet()
        val to = toTypes?.takeIf { it.isNotEmpty() }?.toHashSet()
        return detailSource.get().derived.filter { edge ->
            (from == null || edge.fromType in from) &&
                (to == null || edge.toType in to) &&
                (relationship == null || edge.relationship == relationship)
        }
    }

    /** Bundle a model with its (freshly built) search index, data hash and build [generation]. Pure. */
    private fun buildSnapshotFrom(model: HGModel, graphStore: GraphStore?, memberHandles: Map<Long, String>, generation: Long): GraphSnapshot =
        GraphSnapshot(model, NodeSearchIndex.build(model), DataHash.fingerprint(model), graphStore, memberHandles, generation)
}