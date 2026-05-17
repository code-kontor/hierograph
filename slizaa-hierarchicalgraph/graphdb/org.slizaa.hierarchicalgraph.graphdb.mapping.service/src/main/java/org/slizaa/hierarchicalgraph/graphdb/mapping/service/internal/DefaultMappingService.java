package org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactory;
import org.slizaa.hierarchicalgraph.core.model.INodeSource;
import org.slizaa.hierarchicalgraph.core.model.impl.ExtendedHGRootNodeImpl;
import org.slizaa.hierarchicalgraph.core.model.spi.INodeComparator;
import org.slizaa.hierarchicalgraph.core.model.spi.IProxyDependencyResolver;
import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.IBoltClientAware;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.IMappingParticipator;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.IMappingService;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.MappingException;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.*;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbHierarchicalgraphFactory;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbRootNodeSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal.GraphFactoryFunctions.*;

public class DefaultMappingService implements IMappingService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMappingService.class);

    private List<IMappingParticipator> _mappingParticipators = new CopyOnWriteArrayList<>();

    static Function<Long, INodeSource> createNodeSourceFunction = (id) -> {
        INodeSource nodeSource = GraphDbHierarchicalgraphFactory.eINSTANCE.createGraphDbNodeSource();
        nodeSource.setIdentifier(id);
        return nodeSource;
    };

    public void addMappingParticipator(IMappingParticipator mappingParticipator) {
        this._mappingParticipators.add(mappingParticipator);
    }

    public void removeMappingParticipator(IMappingParticipator mappingParticipator) {
        this._mappingParticipators.remove(mappingParticipator);
    }

    @Override
    public HGRootNode convert(IMappingProvider mappingDescriptor, final IBoltClient boltClient) throws MappingException {

        checkNotNull(mappingDescriptor);
        checkNotNull(boltClient);

        try {
            long totalStart = System.currentTimeMillis();
            long stepStart;

            // create the root element
            final HGRootNode rootNode = HierarchicalgraphFactory.eINSTANCE.createHGRootNode();
            rootNode.registerExtension(IBoltClient.class, boltClient);
            GraphDbRootNodeSource rootNodeSource = GraphDbHierarchicalgraphFactory.eINSTANCE.createGraphDbRootNodeSource();
            rootNodeSource.setIdentifier(-1l);
            rootNodeSource.setBoldClient(boltClient);
            rootNode.setNodeSource(rootNodeSource);

            // process root, hierarchy and dependency queries
            stepStart = System.currentTimeMillis();
            IHierarchyDefinitionProvider hierarchyProvider = initializeBoltClientAwareMappingProviderComponent(
                    mappingDescriptor.getHierarchyDefinitionProvider(), boltClient);
            log.info("Initialized hierarchy provider in {}ms", System.currentTimeMillis() - stepStart);

            if (hierarchyProvider != null) {

                stepStart = System.currentTimeMillis();
                List<Long> rootNodes = hierarchyProvider.getToplevelNodeIds();
                createFirstLevelElements(rootNodes.toArray(new Long[0]), rootNode, createNodeSourceFunction);
                log.info("Created {} root nodes in {}ms", rootNodes.size(), System.currentTimeMillis() - stepStart);

                stepStart = System.currentTimeMillis();
                List<Long[]> parentChildNodeIds = hierarchyProvider.getParentChildNodeIds();
                createHierarchy(parentChildNodeIds, rootNode, createNodeSourceFunction);
                log.info("Created hierarchy ({} parent-child pairs) in {}ms", parentChildNodeIds.size(), System.currentTimeMillis() - stepStart);

                stepStart = System.currentTimeMillis();
                removeDanglingNodes(rootNode);
                log.info("Removed dangling nodes in {}ms", System.currentTimeMillis() - stepStart);

                stepStart = System.currentTimeMillis();
                IDependencyDefinitionProvider dependencyProvider = initializeBoltClientAwareMappingProviderComponent(
                        mappingDescriptor.getDependencyDefinitionProvider(), boltClient);
                log.info("Initialized dependency provider in {}ms", System.currentTimeMillis() - stepStart);

                if (dependencyProvider != null) {
                    stepStart = System.currentTimeMillis();
                    var dependencies = dependencyProvider.getDependencies();
                    createDependencies(dependencies, rootNode,
                            (id, type) -> GraphFactoryFunctions.createDependencySource(id, type, null), false);
                    log.info("Created {} dependencies in {}ms", dependencies.size(), System.currentTimeMillis() - stepStart);
                }
            }

            // register default extensions
            rootNode.registerExtension(IProxyDependencyResolver.class, new CustomProxyDependencyResolver());
            rootNode.registerExtension(IMappingProvider.class, mappingDescriptor);
            rootNode.registerExtension(INodeComparator.class, mappingDescriptor.getNodeComparator());
            rootNode.registerExtension(ILabelDefinitionProvider.class, mappingDescriptor.getLabelDefinitionProvider());
            rootNode.registerExtension(INodeMetadataProvider.class, mappingDescriptor.getNodeMetadataProvider());

            for (IMappingParticipator mappingParticipator : this._mappingParticipators) {
                mappingParticipator.postCreate(rootNode, mappingDescriptor, boltClient);
            }

            log.info("Graph conversion completed in {}ms", System.currentTimeMillis() - totalStart);
            return rootNode;
        }
        catch (Exception e) {
            throw new MappingException(e.getMessage(), e);
        }
    }

    private <T> T initializeBoltClientAwareMappingProviderComponent(T component, final IBoltClient boltClient) throws Exception {

        if (component instanceof IBoltClientAware) {
            ((IBoltClientAware) component).initialize(boltClient);
        }

        return component;
    }

    private void removeDanglingNodes(final HGRootNode rootNode) {
        List<Object> nodeKeys2Remove = ((ExtendedHGRootNodeImpl) rootNode).getIdToNodeMap().entrySet().stream()
                .filter((n) -> {
                    try {
                        return !new Long(0).equals(n.getValue().getIdentifier()) && n.getValue().getRootNode() == null;
                    } catch (Exception e) {
                        return true;
                    }
                }).map(n -> n.getKey()).collect(Collectors.toList());
        nodeKeys2Remove.forEach(k -> ((ExtendedHGRootNodeImpl) rootNode).getIdToNodeMap().remove(k));
    }
}
