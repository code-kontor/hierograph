package org.slizaa.mcp.core

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slizaa.core.boltclient.IBoltClient
import org.slizaa.core.boltclient.IBoltClientFactory
import org.slizaa.hierarchicalgraph.core.model.HGRootNode
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.MappingFactory
import org.slizaa.jqassistant.hierarchicalgraph.JQAssistantMappingProvider
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
        rootNode = MappingFactory.createMappingServiceForStandaloneSetup()
            .convert(mappingProvider, boltClient)

        val children = rootNode.children

        log.info("Hierarchical graph created with {} root children.", children.size)
    }

    @PreDestroy
    fun shutdown() {
        if (::boltClient.isInitialized) {
            boltClient.disconnect()
        }
    }
}
