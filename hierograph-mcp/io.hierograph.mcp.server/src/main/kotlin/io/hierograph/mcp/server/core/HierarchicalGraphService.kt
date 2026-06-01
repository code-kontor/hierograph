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
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import kotlin.time.measureTimedValue

@Service
class HierarchicalGraphService {

    companion object {
        private val log = LoggerFactory.getLogger(HierarchicalGraphService::class.java)
    }

    @Value("\${hierograph.bolt.uri:bolt://localhost:7687}")
    private lateinit var boltUri: String

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
    }

    @PreDestroy
    fun shutdown() {
        if (::boltClient.isInitialized) {
            boltClient.disconnect()
        }
    }
}