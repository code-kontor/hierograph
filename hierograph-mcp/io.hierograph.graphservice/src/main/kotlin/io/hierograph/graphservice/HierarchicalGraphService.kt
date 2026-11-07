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
package io.hierograph.graphservice

import io.hierograph.boltclient.IBoltClient
import io.hierograph.boltclient.IBoltClientFactory
import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.PlaceholderPolicy
import io.hierograph.graphstore.hgmodel.GraphStoreToHGModel
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.core.model.NodeHandle
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.hierarchicalgraph.graphdb.mapping.service.DefaultMappingService
import io.hierograph.mcp.jqa.hierarchicalgraph.jQAssistantMappingProvider
import io.hierograph.graphservice.pagination.DataHash
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.measureTimedValue

@Service
class HierarchicalGraphService {

    companion object {
        private val log = LoggerFactory.getLogger(HierarchicalGraphService::class.java)
    }

    @Value("\${hierograph.bolt.uri:bolt://localhost:7687}")
    private lateinit var boltUri: String

    @Value("\${hierograph.graph.dumpFile:hierarchical-graph.txt}")
    private lateinit var graphDumpFile: String

    /**
     * Where the hierarchical graph is built from: `parquet` (default — from snapshot files, no Neo4j) or
     * the **legacy** `jqa` source (Neo4j via `GraphDbMapping`, retained for validation/bootstrapping until
     * the ASM scanner is proven equivalent).
     */
    @Value("\${hierograph.graph.source:parquet}")
    private lateinit var graphSource: String

    /** Directory holding the Parquet tables, used when `hierograph.graph.source=parquet`. */
    @Value("\${hierograph.graph.parquetDir:parquet}")
    private lateinit var parquetDir: String

    private val useParquet: Boolean get() = graphSource.equals("parquet", ignoreCase = true)

    /** Whether the served graph is built from Parquet (vs the Neo4j/jQA projection). */
    val isParquetBacked: Boolean get() = useParquet

    /** Directory holding the Parquet tables (incl. `detail_refs.parquet`); meaningful only when [isParquetBacked]. */
    val parquetDirectory: String get() = parquetDir

    /** The type-level GraphStore backing the current snapshot in `parquet` mode, else `null`. */
    val graphStore: GraphStore? get() = snapshotRef.get()?.graphStore

    /** Reverse index `node handle → member key` for the Parquet detail tools; empty in `jqa` mode. */
    val memberHandleIndex: Map<Long, String> get() = snapshotRef.get()?.memberHandles ?: emptyMap()

    /**
     * The node handle for a bare member key (`<owner fqn>#<signature>`, as detail refs carry it): the
     * artifact-scoped member node's handle when the member is in the graph, else a stable but
     * non-navigable handle of the bare key (e.g. a member of an external type).
     */
    fun memberHandle(memberKey: String): Long =
        graphStore?.memberByKey(memberKey)?.key?.let { NodeHandle.of(it) } ?: NodeHandle.of(memberKey)

    lateinit var boltClient: IBoltClient

    /**
     * The currently-served graph snapshot. Swapped atomically by [reload]; every read below goes
     * through it, so concurrent tool calls always see one consistent (model, index, hash) set.
     */
    private val snapshotRef = AtomicReference<GraphSnapshot>()

    val model: HGModel get() = current().model

    val searchIndex: NodeSearchIndex get() = current().searchIndex

    /** Fingerprint of the currently-loaded snapshot; changes on every successful [reload]. */
    val dataHash: String get() = current().dataHash

    /**
     * Whether a hierarchical graph is currently loaded. `false` when the server was started without a
     * reachable database (see [init]); tools consult this to decide whether they can answer at all.
     */
    val isGraphAvailable: Boolean get() = snapshotRef.get() != null

    private fun current(): GraphSnapshot =
        snapshotRef.get() ?: error("Hierarchical graph has not been loaded yet")

    @PostConstruct
    fun init() {
        val boltClientFactory = IBoltClientFactory.newInstance(Executors.newFixedThreadPool(4))
        boltClient = boltClientFactory.createBoltClient(boltUri)

        // Bolt is required for the `jqa` source and is also used by the detail-level tools, so connect
        // best-effort either way. In `parquet` mode the graph builds from files and does not need it.
        log.info("Connecting bolt client to: {} (graph source = {})", boltUri, graphSource)
        runCatching { boltClient.connect() }.onFailure { log.warn("Bolt connect to {} failed: {}", boltUri, it.message) }

        // Start "empty" if the graph cannot be built: build best-effort, but never fail startup. Tools
        // then report that no graph is available until one is loaded (e.g. via reload_graph).
        try {
            snapshotRef.set(loadSnapshot())
        } catch (e: Exception) {
            log.warn(
                "No hierarchical graph loaded at startup (source={}): {}. The server is starting empty; " +
                        "tools will report that no graph is available until one is loaded (e.g. via reload_graph).",
                graphSource, e.message,
            )
        }
    }

    /**
     * Rebuild the graph from the (already-connected) Bolt store and swap it in atomically, without
     * restarting the server. Call this after a re-scan repopulates the store so the tools reflect the
     * current code. The existing Bolt connection is reused — the Neo4j driver re-establishes sessions
     * to the (possibly restarted) Bolt server on demand.
     *
     * The new snapshot is built fully before the swap, so on failure the previously loaded graph keeps
     * being served and an `error` status is returned rather than leaving the server empty. Note that
     * pagination cursors issued against the previous snapshot become stale (their data hash no longer
     * matches) and must be re-requested.
     */
    fun reload(): Map<String, Any?> {
        log.info("Reloading hierarchical graph from bolt store: {}", boltUri)
        return try {
            // The server may have started empty (no database reachable at startup), leaving the bolt
            // client unconnected. Connect on demand so a reload can load the first graph.
            if (!boltClient.isConnected) {
                log.info("Bolt client not connected; connecting to {} before reload.", boltUri)
                runCatching { boltClient.connect() }.onFailure { log.warn("Bolt connect failed: {}", it.message) }
            }
            val (snapshot, elapsed) = measureTimedValue { loadSnapshot() }
            snapshotRef.set(snapshot)
            val rootChildren = snapshot.model.hierarchy.let { it.childrenOf(it.rootNode).size }
            log.info("Reload complete: {} root children, hash {} in {}.", rootChildren, snapshot.dataHash, elapsed)
            linkedMapOf(
                "status" to "reloaded",
                "root_children" to rootChildren,
                "search_index_entries" to snapshot.searchIndex.entries.size,
                "data_hash" to snapshot.dataHash,
                "reload_millis" to elapsed.inWholeMilliseconds
            )
        } catch (e: Exception) {
            log.error("Reload failed; keeping the previously loaded graph.", e)
            linkedMapOf(
                "status" to "error",
                "message" to (e.message ?: e.javaClass.simpleName),
                "data_hash" to snapshotRef.get()?.dataHash,
                "note" to "The previously loaded graph is still being served; re-run the scan and retry."
            )
        }
    }

    /**
     * Seed the service with a ready-made model without going through Bolt. Builds the derived index
     * and data hash and installs them as the current snapshot. Intended for non-Spring construction
     * in tests; production code loads via [init] / [reload].
     */
    fun seed(model: HGModel) {
        snapshotRef.set(buildSnapshotFrom(model, graphStore = null, memberHandles = emptyMap()))
    }

    /** Build a fresh snapshot from the configured source, logging progress and writing the dump. */
    private fun loadSnapshot(): GraphSnapshot {
        log.info("Creating hierarchical graph (source = {})...", graphSource)
        val (snapshot, elapsed) = measureTimedValue {
            buildSnapshot()
        }

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

        // Dump the hierarchical graph to a file: one indented line per node, with name/fqn.
        val outputFile = File(graphDumpFile)
        outputFile.bufferedWriter().use { writer ->
            TreeTraverser.dumpTree(hierarchy.rootNode, hierarchy, sink = { writer.appendLine(it) })
        }
        log.info("Hierarchical graph written to: {}", outputFile.absolutePath)

        return snapshot
    }

    /**
     * Builds a snapshot from the configured source. The default `parquet` path reads the Parquet tables
     * into a [GraphStore] and bridges to an [HGModel] (no Neo4j), carrying the store so the detail path
     * can read it.
     *
     * The `else` branch is the **legacy jQAssistant/Neo4j path** (`GraphDbMapping` over Bolt), retained
     * only to validate/bootstrap the Parquet path until the ASM scanner is proven equivalent; it will be
     * removed together with the `graphstore.jqa` module and the Cypher tool implementations.
     */
    private fun buildSnapshot(): GraphSnapshot =
        if (useParquet) {
            val dir = Path.of(parquetDir)
            val store = GraphStore(PlaceholderPolicy.JQA_ALIGNED)
            val reader = ParquetReader()
            reader.load(dir, store)
            // Reverse index for the detail tools: member node handle -> bare member key (the snapshot key).
            val memberHandles = store.nodes().asSequence()
                .filter { it.kind in JavaKinds.MEMBER_KINDS }
                .associateBy({ NodeHandle.of(it.key) }, { it.fqn })
            buildSnapshotFrom(GraphStoreToHGModel.build(store), store, memberHandles)
        } else {
            // Legacy: Neo4j/jQAssistant projection (see the KDoc above).
            buildSnapshotFrom(DefaultMappingService().convert(jQAssistantMappingProvider(), boltClient), graphStore = null, memberHandles = emptyMap())
        }

    /** Bundle a model with its (freshly built) search index and data hash. Pure — no I/O, no logging. */
    private fun buildSnapshotFrom(model: HGModel, graphStore: GraphStore?, memberHandles: Map<Long, String>): GraphSnapshot =
        GraphSnapshot(model, NodeSearchIndex.build(model), DataHash.fingerprint(model), graphStore, memberHandles)

    @PreDestroy
    fun shutdown() {
        if (::boltClient.isInitialized && boltClient.isConnected) {
            boltClient.disconnect()
        }
    }
}