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
import io.hierograph.hierarchicalgraph.core.model.HGNodeTraverser
import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import io.hierograph.hierarchicalgraph.graphdb.mapping.service.DefaultMappingService
import io.hierograph.mcp.jqa.hierarchicalgraph.jQAssistantMappingProvider
import io.hierograph.mcp.server.modulith.ModulithModel
import io.hierograph.mcp.server.modulith.ModulithOverlay
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.Executors
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
     * Path to a Spring Modulith `modulith-model.json` (produced by the scanned app's own
     * ApplicationModules analysis). When set and present, module boundaries and exposed types are
     * overlaid onto the graph and cross-module boundary violations are flagged. Empty = disabled.
     */
    @Value("\${hierograph.modulith.model:}")
    private lateinit var modulithModelPath: String

    lateinit var boltClient: IBoltClient

    lateinit var rootNode: HGRootNode

    @PostConstruct
    fun init() {
        log.info("Connecting bolt client to: {}", boltUri)

        val boltClientFactory = IBoltClientFactory.newInstance(Executors.newFixedThreadPool(4))
        boltClient = boltClientFactory.createBoltClient(boltUri)
        boltClient.connect()

        log.info("Creating hierarchical graph...")
        val (createdRoot, elapsed) = measureTimedValue {
            DefaultMappingService().convert(jQAssistantMappingProvider(), boltClient)
        }
        rootNode = createdRoot

        applyModulithOverlay(rootNode)

        val children = rootNode.children
        log.info("Hierarchical graph created with {} root children in {}.", children.size, elapsed)

        // Count nodes by kind (skip the root node itself)
        val kindCounts = mutableMapOf<Any?, Int>()
        for (child in rootNode.children) {
            HGNodeTraverser.traverse(child) { node ->
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
            TreeTraverser.dumpTree(rootNode, sink = { writer.appendLine(it) })
        }
        log.info("Hierarchical graph written to: {}", outputFile.absolutePath)
    }

    /**
     * Overlays the authoritative Spring Modulith model (if configured and present) onto the graph,
     * flagging cross-module boundary violations. No-op when [modulithModelPath] is unset or missing,
     * so non-modulith projects are unaffected.
     */
    private fun applyModulithOverlay(root: HGRootNode) {
        if (modulithModelPath.isBlank()) return
        val file = File(modulithModelPath)
        if (!file.isFile) {
            log.warn("Spring Modulith model not found at '{}' — skipping overlay.", file.absolutePath)
            return
        }
        val model = ModulithModel.read(file)
        val stats = ModulithOverlay.apply(root, model)
        log.info(
            "Spring Modulith overlay applied from '{}': {} modules, {} cross-module edges, {} boundary violations.",
            file.name, model.moduleCount, stats.crossModuleEdges, stats.violations
        )
    }

    @PreDestroy
    fun shutdown() {
        if (::boltClient.isInitialized) {
            boltClient.disconnect()
        }
    }
}