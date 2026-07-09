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
package io.hierograph.mcp.server.core

import io.hierograph.boltclient.IBoltClient
import io.hierograph.boltclient.IBoltClientFactory
import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.graphdb.mapping.service.DefaultMappingService
import io.hierograph.mcp.jqa.hierarchicalgraph.jQAssistantMappingProvider
import io.hierograph.mcp.server.core.pagination.DataHash
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
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

    private fun current(): GraphSnapshot =
        snapshotRef.get() ?: error("Hierarchical graph has not been loaded yet")

    @PostConstruct
    fun init() {
        log.info("Connecting bolt client to: {}", boltUri)

        val boltClientFactory = IBoltClientFactory.newInstance(Executors.newFixedThreadPool(4))
        boltClient = boltClientFactory.createBoltClient(boltUri)
        boltClient.connect()

        snapshotRef.set(loadSnapshot())
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
        snapshotRef.set(buildSnapshotFrom(model))
    }

    /** Query the connected Bolt store into a fresh snapshot, logging progress and writing the dump. */
    private fun loadSnapshot(): GraphSnapshot {
        log.info("Creating hierarchical graph...")
        val (snapshot, elapsed) = measureTimedValue {
            val createdModel = DefaultMappingService().convert(jQAssistantMappingProvider(), boltClient)
            buildSnapshotFrom(createdModel)
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

    /** Bundle a model with its (freshly built) search index and data hash. Pure — no I/O, no logging. */
    private fun buildSnapshotFrom(model: HGModel): GraphSnapshot =
        GraphSnapshot(model, NodeSearchIndex.build(model), DataHash.fingerprint(model))

    @PreDestroy
    fun shutdown() {
        if (::boltClient.isInitialized) {
            boltClient.disconnect()
        }
    }
}