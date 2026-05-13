package org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal;

import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.core.progressmonitor.IProgressMonitor;
import org.slizaa.core.progressmonitor.NullProgressMonitor;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactory;
import org.slizaa.hierarchicalgraph.core.model.INodeSource;
import org.slizaa.hierarchicalgraph.core.model.impl.ExtendedHGRootNodeImpl;
import org.slizaa.hierarchicalgraph.core.model.spi.IAutoExpandInterceptor;
import org.slizaa.hierarchicalgraph.core.model.spi.INodeComparator;
import org.slizaa.hierarchicalgraph.core.model.spi.IProxyDependencyResolver;
import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.IBoltClientAware;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.IMappingParticipator;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.IMappingService;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.MappingException;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinitionProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ILabelDefinitionProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbHierarchicalgraphFactory;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbNodeSource;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbRootNodeSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal.GraphFactoryFunctions.*;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class DefaultMappingService implements IMappingService {

    /**
     * -
     */
    private List<IMappingParticipator> _mappingParticipators = new CopyOnWriteArrayList<>();

    /**
     * create the node source creator function
     */
    static Function<Long, INodeSource> createNodeSourceFunction = (id) -> {

        // create the node source
        INodeSource nodeSource = GraphDbHierarchicalgraphFactory.eINSTANCE
                .createGraphDbNodeSource();
        nodeSource.setIdentifier(id);

        // return the result
        return nodeSource;
    };

    public void addMappingParticipator(IMappingParticipator mappingParticipator) {
        this._mappingParticipators.add(mappingParticipator);
    }

    public void removeMappingParticipator(IMappingParticipator mappingParticipator) {
        this._mappingParticipators.remove(mappingParticipator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HGRootNode convert(IMappingProvider mappingDescriptor, final IBoltClient boltClient,
                              IProgressMonitor progressMonitor) throws MappingException {

        checkNotNull(mappingDescriptor);
        checkNotNull(boltClient);

        //
        if (progressMonitor == null) {
            progressMonitor = new NullProgressMonitor();
        }

        try {
            // create the root element
            final HGRootNode rootNode = HierarchicalgraphFactory.eINSTANCE.createHGRootNode();
            rootNode.registerExtension(IBoltClient.class, boltClient);
            GraphDbRootNodeSource rootNodeSource = GraphDbHierarchicalgraphFactory.eINSTANCE.createGraphDbRootNodeSource();
            rootNodeSource.setIdentifier(-1l);
            rootNodeSource.setBoldClient(boltClient);
            rootNode.setNodeSource(rootNodeSource);

            // process root, hierarchy and dependency queries
            IHierarchyDefinitionProvider hierarchyProvider = initializeBoltClientAwareMappingProviderComponent(
                    mappingDescriptor.getHierarchyDefinitionProvider(), boltClient, progressMonitor);

            if (hierarchyProvider != null) {

                //
                progressMonitor.step("Requesting root nodes...");
                List<Long> rootNodes = hierarchyProvider.getToplevelNodeIds();

                createFirstLevelElements(rootNodes.toArray(new Long[0]), rootNode, createNodeSourceFunction, progressMonitor.subTask("Creating root nodes...")
                    .withParentConsumptionInPercentage(15)
                    .withTotalWorkTicks(100)
                    .create());

                //
                progressMonitor.step("Requesting nodes...");
                List<Long[]> parentChildNodeIds = hierarchyProvider.getParentChildNodeIds();

                createHierarchy(parentChildNodeIds, rootNode, createNodeSourceFunction, progressMonitor.subTask("Creating nodes...")
                    .withParentConsumptionInPercentage(40)
                    .withTotalWorkTicks(100)
                    .create());

                // filter 'dangling' nodes
                removeDanglingNodes(rootNode);

                //
                IDependencyDefinitionProvider dependencyProvider = initializeBoltClientAwareMappingProviderComponent(
                        mappingDescriptor.getDependencyDefinitionProvider(), boltClient, progressMonitor);

                if (dependencyProvider != null) {

                    //
                    createDependencies(dependencyProvider.getDependencies(), rootNode,
                            (id, type) -> GraphFactoryFunctions.createDependencySource(id, type, null), false, progressMonitor.subTask("Creating dependencies...")
                            .withParentConsumptionInPercentage(45)
                            .withTotalWorkTicks(100)
                            .create());
                }
            }

            // register default extensions
            rootNode.registerExtension(IProxyDependencyResolver.class, new CustomProxyDependencyResolver());
            rootNode.registerExtension(IMappingProvider.class, mappingDescriptor);
            rootNode.registerExtension(INodeComparator.class, mappingDescriptor.getNodeComparator());
            rootNode.registerExtension(ILabelDefinitionProvider.class, mappingDescriptor.getLabelDefinitionProvider());

            //
            rootNode.registerExtension(IAutoExpandInterceptor.class, node -> {

                Optional<GraphDbNodeSource> nodeSource = node.getNodeSource(GraphDbNodeSource.class);
                if (nodeSource.isPresent()) {
                    // TODO
                    return nodeSource.get().getLabels().contains("Resource");
                }
                return false;
            });

            //
            for (IMappingParticipator mappingParticipator : this._mappingParticipators) {
                mappingParticipator.postCreate(rootNode, mappingDescriptor, boltClient);
            }

            //
            return rootNode;
        }
        //
        catch (Exception e) {
            throw new MappingException(e.getMessage(), e);
        }
    }

    /**
     * <p>
     * </p>
     *
     * @param boltClient
     * @param progressMonitor
     * @throws Exception
     */
    private <T> T initializeBoltClientAwareMappingProviderComponent(T component, final IBoltClient boltClient,
                                                                    IProgressMonitor progressMonitor) throws Exception {

        if (component instanceof IBoltClientAware) {
            ((IBoltClientAware) component).initialize(boltClient, progressMonitor);
        }

        return component;
    }

    /**
     * <p>
     * </p>
     *
     * @param rootNode
     */
    private void removeDanglingNodes(final HGRootNode rootNode) {

        //
        List<Object> nodeKeys2Remove = ((ExtendedHGRootNodeImpl) rootNode).getIdToNodeMap().entrySet().stream()
                .filter((n) -> {
                    try {
                        return !new Long(0).equals(n.getValue().getIdentifier()) && n.getValue().getRootNode() == null;
                    } catch (Exception e) {
                        return true;
                    }
                }).map(n -> n.getKey()).collect(Collectors.toList());

        //
        nodeKeys2Remove.forEach(k -> ((ExtendedHGRootNodeImpl) rootNode).getIdToNodeMap().remove(k));
    }

    /**
     * <p>
     * </p>
     *
     * @param iterationMonitor
     * @param taskName
     */
    private void report(IProgressMonitor iterationMonitor, String taskName) {
        if (iterationMonitor != null) {
            iterationMonitor.step(taskName);
        }
    }
}
