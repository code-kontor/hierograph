package io.hierograph.mcp.server.core

import io.hierograph.boltclient.IBoltClient
import io.hierograph.boltclient.IBoltClientFactory
import io.hierograph.hierarchicalgraph.core.model.HGNodeTraverser
import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import io.hierograph.hierarchicalgraph.graphdb.mapping.service.DefaultMappingService
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantMappingProvider
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.Executors

@Service
class HierarchicalGraphService {

    companion object {
        private val log = LoggerFactory.getLogger(HierarchicalGraphService::class.java)
    }

    @Value("\${slizaa.bolt.uri:bolt://localhost:7687}")
    private lateinit var boltUri: String

    lateinit var boltClient: IBoltClient

    lateinit var rootNode: HGRootNode

    @PostConstruct
    fun init() {
        log.info("Connecting bolt client to: {}", boltUri)

        val boltClientFactory = IBoltClientFactory.newInstance(Executors.newFixedThreadPool(4))
        boltClient = boltClientFactory.createBoltClient(boltUri)
        boltClient.connect()

        val mappingProvider = JQAssistantMappingProvider()

        log.info("Creating hierarchical graph using '{}' ...", mappingProvider.javaClass.name)
        rootNode = DefaultMappingService()
            .convert(mappingProvider, boltClient)

        val children = rootNode.children

        log.info("Hierarchical graph created with {} root children.", children.size)

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