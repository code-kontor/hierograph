package org.slizaa.mcp.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.core.boltclient.IBoltClientFactory;
import org.slizaa.hierarchicalgraph.core.algorithms.impl.DependencyStructureMatrix;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.MappingFactory;
import org.slizaa.jqassistant.hierarchicalgraph.JQAssistant_MappingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;

@Service
public class HierarchicalGraphService {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalGraphService.class);

    @Value("${slizaa.bolt.uri:bolt://localhost:7687}")
    private String boltUri;

    private IBoltClient boltClient;
    private HGRootNode rootNode;

    @PostConstruct
    public void init() throws Exception {

        log.info("Connecting bolt client to: {}", boltUri);

        IBoltClientFactory boltClientFactory = IBoltClientFactory.newInstance(Executors.newFixedThreadPool(4));
        boltClient = boltClientFactory.createBoltClient(boltUri);
        boltClient.connect();

        log.info("Creating jQAssistant hierarchical graph...");
        rootNode = MappingFactory.createMappingServiceForStandaloneSetup()
                .convert(new JQAssistant_MappingProvider(), boltClient);

        var children = rootNode.getChildren();

        log.info("Hierarchical graph created with {} root children.", children.size());
    }

    @PreDestroy
    public void shutdown() {
        if (boltClient != null) {
            boltClient.disconnect();
        }
    }

    public HGRootNode getRootNode() {
        return rootNode;
    }

    public IBoltClient getBoltClient() {
        return boltClient;
    }
}
