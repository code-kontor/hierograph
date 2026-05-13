package org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal;

import org.neo4j.driver.types.Node;
import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.core.progressmonitor.IProgressMonitor;
import org.slizaa.hierarchicalgraph.core.model.*;
import org.slizaa.hierarchicalgraph.core.model.impl.ExtendedHGRootNodeImpl;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinition;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IProxyDependencyDefinition;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbDependencySource;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbHierarchicalgraphFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class GraphFactoryFunctions {

  /**
   * <p>
   * </p>
   *
   * @param id
   * @param type
   * @return
   */
  public static GraphDbDependencySource createDependencySource(Long id, String type) {
    return createDependencySource(checkNotNull(id), checkNotNull(type), null);
  }

  /**
   * <p>
   * </p>
   *
   * @param id
   * @param type
   * @param userObject
   * @return
   */
  public static GraphDbDependencySource createDependencySource(Long id, String type, Object userObject) {

    checkNotNull(id);
    checkNotNull(type);

    // create the dependency source
    GraphDbDependencySource dependencySource = GraphDbHierarchicalgraphFactory.eINSTANCE
        .createGraphDbDependencySource();

    dependencySource.setIdentifier(id);
    dependencySource.setType(type);
    dependencySource.setUserObject(userObject);

    return dependencySource;
  }

  /**
   * <p>
   * </p>
   *
   * @param rootElement
   * @param nodeSourceCreator
   */
  public static void createFirstLevelElements(Long[] firstLevelNodeIds, HGRootNode rootElement,
      final Function<Long, INodeSource> nodeSourceCreator, IProgressMonitor progressMonitor) {

    checkNotNull(firstLevelNodeIds);
    checkNotNull(rootElement);
    checkNotNull(nodeSourceCreator);

    // create sub monitor
    final IProgressMonitor subMonitor = progressMonitor != null
        ? progressMonitor.subTask("Creating first level elements...")
            .withParentConsumptionInPercentage(100)
            .withTotalWorkTicks(firstLevelNodeIds.length)
            .create()
        : null;

    for (int i = 0; i < firstLevelNodeIds.length; i++) {

      // increase sub monitor
      if (subMonitor != null) {
        subMonitor.advance(1);
      }

      createNodeIfAbsent(firstLevelNodeIds[i], rootElement, rootElement, nodeSourceCreator);
    }
  }

  /**
   * <p>
   * </p>
   */
  public static void createHierarchy(List<Long[]> hierarchyNodeIds, HGRootNode rootElement,
      final Function<Long, INodeSource> nodeSourceCreator, IProgressMonitor progressMonitor) {

    checkNotNull(hierarchyNodeIds);

    // create sub monitor
    final IProgressMonitor subMonitor = progressMonitor != null
            ? progressMonitor.subTask("Creating hierarchy...")
            .withParentConsumptionInPercentage(100)
            .withTotalWorkTicks(hierarchyNodeIds.size())
            .create()
            : null;

    //
    for (Long[] ids : hierarchyNodeIds) {

      // increase sub monitor
      if (subMonitor != null) {
        subMonitor.advance(1);
      }

      //
      HGNode parentNode = createNodeIfAbsent(ids[0], rootElement, null, nodeSourceCreator);
      createNodeIfAbsent(ids[1], rootElement, parentNode, nodeSourceCreator);
    }
  }

  /**
   * <p>
   * </p>
   *
   * @param rootElement
   * @param dependencySourceCreator
   */
  public static List<HGCoreDependency> createDependencies(List<IDependencyDefinition> dependencies,
      HGRootNode rootElement, BiFunction<Long, String, IDependencySource> dependencySourceCreator,
      boolean reinitializeCaches, IProgressMonitor progressMonitor) {

    // create sub monitor
    final IProgressMonitor subMonitor = progressMonitor != null
            ? progressMonitor.subTask("Creating dependencies...")
            .withParentConsumptionInPercentage(100)
            .withTotalWorkTicks(dependencies.size())
            .create()
            : null;

    //
    List<HGCoreDependency> result = new LinkedList<HGCoreDependency>();

    //
    dependencies.forEach((element) -> {

      // increase sub monitor
      if (subMonitor != null) {
        subMonitor.advance(1);
      }

      //
      if (element instanceof IProxyDependencyDefinition) {

        //
        IProxyDependencyDefinition proxyDependency = (IProxyDependencyDefinition) element;

        //
        Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> resolveFunction = checkNotNull(
            proxyDependency.getResolveFunction());

        //
        HGCoreDependency slizaaProxyDependency = createDependency(proxyDependency.getIdStart(),
            proxyDependency.getIdTarget(), proxyDependency.getIdRel(), proxyDependency.getType(), rootElement,
            dependencySourceCreator, resolveFunction, reinitializeCaches);

        //
        if (slizaaProxyDependency != null) {

          // TODO: Should we really use the user object here?
          ((GraphDbDependencySource) slizaaProxyDependency.getDependencySource())
              .setUserObject(proxyDependency.getResolveFunction());

          //
          result.add(slizaaProxyDependency);
        }

        //
        else {

          // TODO!

          //
          IBoltClient boltClient = rootElement.getExtension(IBoltClient.class);
          Node startNode = boltClient.getNode(proxyDependency.getIdStart());
          Node targetNode = boltClient.getNode(proxyDependency.getIdTarget());

          System.out.println(
              "Dependency is null for " + proxyDependency.getIdStart() + " : " + proxyDependency.getIdTarget());
          System.out.println(startNode.labels() + " : " + startNode.asMap());
          System.out.println(targetNode.labels() + " : " + targetNode.asMap());
        }

      }

      //
      else if (element instanceof IDependencyDefinition) {

        //
        IDependencyDefinition simpleDependency = element;

        //
        result.add(createDependency(simpleDependency.getIdStart(), simpleDependency.getIdTarget(),
            simpleDependency.getIdTarget(), simpleDependency.getType(), rootElement, dependencySourceCreator, null,
            reinitializeCaches));
      }
    });

    //
    return result;
  }

  /**
   * <p>
   * </p>
   *
   * @param from
   * @param to
   * @param type
   * @return
   */
  public static HGCoreDependency createDependency(Long from, Long to, Long idRel, String type, HGRootNode rootElement,
      BiFunction<Long, String, IDependencySource> dependencySourceCreator,
      Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> resolveFunction,
      boolean reinitializeCaches) {

    // get the from...
    HGNode fromElement = ((ExtendedHGRootNodeImpl) rootElement).getIdToNodeMap().get(from);
    if (fromElement == null) {
      return null;
    }

    // ...and to nodes
    HGNode toElement = ((ExtendedHGRootNodeImpl) rootElement).getIdToNodeMap().get(to);
    if (toElement == null) {
      return null;
    }

    //
    if (resolveFunction != null) {
      return HierarchicalgraphFactoryFunctions.createNewProxyDependency(fromElement, toElement, type,
          () -> dependencySourceCreator.apply(idRel, type), reinitializeCaches);
    }

    //
    else {
      return HierarchicalgraphFactoryFunctions.createNewCoreDependency(fromElement, toElement, type,
          () -> dependencySourceCreator.apply(idRel, type), reinitializeCaches);
    }
  }

  /**
   * <p>
   * </p>
   *
   * @param identifier
   * @param parent
   * @param nodeSourceCreator
   * @return
   */
  private static HGNode createNodeIfAbsent(final Long identifier, final HGNode rootNode, final HGNode parent,
      final Function<Long, INodeSource> nodeSourceCreator) {

    checkNotNull(identifier);

    //
    HGNode newNode = ((ExtendedHGRootNodeImpl) rootNode).getIdToNodeMap().get(identifier);
    if (newNode == null) {

      // create new node
      newNode = HierarchicalgraphFactory.eINSTANCE.createHGNode();
      newNode.setNodeSource(nodeSourceCreator.apply(identifier));
      newNode.setParent(parent);

      // put in cache
      ((ExtendedHGRootNodeImpl) rootNode).getIdToNodeMap().put(identifier, newNode);
    }

    // may be the node has been created before - so we have to set the parent yet...
    else if (newNode.getParent() == null) {
      newNode.setParent(parent);
    }

    //
    return newNode;
  }

  /**
   * <p>
   * </p>
   *
   * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
   */
  public static class Neo4jRelationship {

    /** - */
    public long   _idStart;

    /** - */
    public long   _idTarget;

    /** - */
    public long   _idRel;

    /** - */
    public String _type;

    public Neo4jRelationship(long idStart, long idTarget, long idRel, String type) {
      this._idStart = idStart;
      this._idTarget = idTarget;
      this._idRel = idRel;
      this._type = type;
    }

    public long getIdStart() {
      return this._idStart;
    }

    public long getIdTarget() {
      return this._idTarget;
    }

    public long getIdRel() {
      return this._idRel;
    }

    public String getType() {
      return this._type;
    }
  }
}
