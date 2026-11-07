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

import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.PlaceholderPolicy
import io.hierograph.graphstore.hgmodel.GraphStoreToHGModel
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.model.DetailRef
import io.hierograph.graphstore.model.JavaKinds
import io.hierograph.graphstore.model.JavaNodeKind
import io.hierograph.graphservice.pagination.DataHash
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.measureTimedValue

@Service
class HierarchicalGraphService {

    companion object {
        private val log = LoggerFactory.getLogger(HierarchicalGraphService::class.java)
    }

    @Value("\${hierograph.graph.dumpFile:hierarchical-graph.txt}")
    private lateinit var graphDumpFile: String

    /** Directory holding the Parquet snapshot tables the graph is built from (no Neo4j). */
    @Value("\${hierograph.graph.parquetDir:parquet}")
    private lateinit var parquetDir: String

    /** Directory holding the Parquet tables (incl. `detail_refs.parquet`). */
    val parquetDirectory: String get() = parquetDir

    /** The type-level GraphStore backing the current snapshot in `parquet` mode, else `null`. */
    val graphStore: GraphStore? get() = snapshotRef.get()?.graphStore

    /** Reverse index `node handle → member key` for the Parquet detail tools. */
    val memberHandleIndex: Map<Long, String> get() = snapshotRef.get()?.memberHandles ?: emptyMap()

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
        // Start "empty" if the graph cannot be built: build best-effort, but never fail startup. Tools
        // then report that no graph is available until one is loaded (e.g. via reload_graph).
        try {
            snapshotRef.set(loadSnapshot())
        } catch (e: Exception) {
            log.warn(
                "No hierarchical graph loaded at startup: {}. The server is starting empty; tools will " +
                        "report that no graph is available until one is loaded (e.g. via reload_graph).",
                e.message,
            )
        }
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
        log.info("Reloading hierarchical graph from Parquet snapshot: {}", parquetDir)
        return try {
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
        snapshotRef.set(buildSnapshotFrom(model, graphStore = null, memberHandles = emptyMap(), derivedDetailRefs = emptyList()))
    }

    /** Build a fresh snapshot from the Parquet snapshot, logging progress and writing the dump. */
    private fun loadSnapshot(): GraphSnapshot {
        log.info("Creating hierarchical graph from Parquet snapshot...")
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
     * Builds a snapshot by reading the Parquet tables into a [GraphStore] and bridging to an [HGModel]
     * (no Neo4j), carrying the store so the detail path can read it.
     */
    private fun buildSnapshot(): GraphSnapshot {
        val dir = Path.of(parquetDir)
        val store = GraphStore(PlaceholderPolicy.JQA_ALIGNED)
        val reader = ParquetReader()
        reader.load(dir, store)
        // Reverse index for the detail tools: member node handle -> bare member key (the snapshot key).
        val memberHandles = store.nodes().asSequence()
            .filter { it.kind in JavaKinds.MEMBER_KINDS }
            .associateBy({ NodeHandle.of(it.key) }, { it.fqn })
        return buildSnapshotFrom(GraphStoreToHGModel.build(store), store, memberHandles, deriveDetailRefs(dir, store, reader))
    }

    /**
     * Hierarchy-dependent member edges (overrides / virtual + resolved calls) computed over the full
     * assembled graph — the load-time home of what the scanner cannot derive across independently-scanned
     * artifacts. Keeps only the edges not already in the snapshot's `detail_refs`, so a single-shot or jQA
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
        val from = fromTypes?.takeIf { it.isNotEmpty() }?.toHashSet()
        val to = toTypes?.takeIf { it.isNotEmpty() }?.toHashSet()
        return current().derivedDetailRefs.filter { edge ->
            (from == null || edge.fromType in from) &&
                (to == null || edge.toType in to) &&
                (relationship == null || edge.relationship == relationship)
        }
    }

    /** Bundle a model with its (freshly built) search index and data hash. Pure — no I/O, no logging. */
    private fun buildSnapshotFrom(model: HGModel, graphStore: GraphStore?, memberHandles: Map<Long, String>, derivedDetailRefs: List<DetailRef>): GraphSnapshot =
        GraphSnapshot(model, NodeSearchIndex.build(model), DataHash.fingerprint(model), graphStore, memberHandles, derivedDetailRefs)
}