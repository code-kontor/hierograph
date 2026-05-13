package org.slizaa.hierarchicalgraph.graphdb.model.impl;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.util.EDataTypeUniqueEList;
import org.eclipse.emf.ecore.util.EcoreEMap;
import org.neo4j.driver.Result;
import org.neo4j.driver.types.Node;
import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphPackage;
import org.slizaa.hierarchicalgraph.core.model.impl.StringToStringMapImpl;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbHierarchicalgraphPackage;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbRootNodeSource;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class ExtendedGraphDbNodeSourceTrait {

  /** - */
  private static final String   BATCH_UPDATE_QUERY = "MATCH (p) where id(p) in { ids } RETURN p";

  /** - */
  private GraphDbNodeSourceImpl _nodeSource;

  /**
   * <p>
   * Creates a new instance of type {@link ExtendedGraphDbNodeSourceTrait}.
   * </p>
   *
   * @param nodeSource
   */
  public ExtendedGraphDbNodeSourceTrait(GraphDbNodeSourceImpl nodeSource) {
    this._nodeSource = nodeSource;
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public EMap<String, String> getProperties() {

    // lazy load...
    if (this._nodeSource.properties == null) {
      reloadNodeAndProperties();
    }

    // return the result
    return this._nodeSource.properties;
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public EList<String> getLabels() {

    // lazy load...
    if (this._nodeSource.labels == null) {
      reloadNodeAndProperties();
    }

    // return the result
    return this._nodeSource.labels;
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public void reloadNodeAndProperties() {

    Node node = getBoltClient().getNode((long) this._nodeSource.getIdentifier());

    // request properties
    setLabels(node);

    // request properties
    setProperties(node);
  }

  /**
   * <p>
   * </p>
   */
  public void loadPropertiesAndLabelsForChildren() {
    batchUpdate(this._nodeSource.getNode().getChildren());
  }

  /**
   * <p>
   * </p>
   *
   * @param nodeProperties
   * @return
   */
  private EList<String> setLabels(Node node) {
    checkNotNull(node);

    // lazy init
    if (this._nodeSource.labels == null) {
      this._nodeSource.labels = new EDataTypeUniqueEList<String>(String.class, this._nodeSource,
          GraphDbHierarchicalgraphPackage.GRAPH_DB_NODE_SOURCE__LABELS);
    } else {
      this._nodeSource.labels.clear();
    }

    node.labels().forEach((e) -> this._nodeSource.labels.add(e));

    // return the result
    return this._nodeSource.labels;
  }

  public void onExpand() {
    loadPropertiesAndLabelsForChildren();
  }

  public void onCollapse() {
  }

  public void onSelect() {

  }

  /**
   * <p>
   * </p>
   *
   * @param jsonObject
   * @return
   */
  private EMap<String, String> setProperties(Node node) {
    checkNotNull(node);

    // lazy init
    if (this._nodeSource.properties == null) {
      this._nodeSource.properties = new EcoreEMap<String, String>(
          HierarchicalgraphPackage.Literals.STRING_TO_STRING_MAP,
         StringToStringMapImpl.class, this._nodeSource,
          GraphDbHierarchicalgraphPackage.GRAPH_DB_NODE_SOURCE__PROPERTIES);
    } else {
      // clear the properties first
      this._nodeSource.properties.clear();
    }

    // re-populate
    node.asMap().entrySet().forEach((e) -> {
      this._nodeSource.properties.put(e.getKey(), e.getValue().toString());
    });

    // return the result
    return this._nodeSource.properties;
  }

  /**
   * <p>
   * </p>
   *
   * @param hgNodes
   */
  private void batchUpdate(List<HGNode> hgNodes) {

    Map<Long, HGNode> nodes = new HashMap<>();
    hgNodes.forEach((n) -> nodes.put((Long) n.getIdentifier(), n));

    // query
    Map<String, Object> params = new HashMap<>();
    params.put("ids", nodes.keySet());
    Future<Result> result = getBoltClient().asyncExecCypherQuery(BATCH_UPDATE_QUERY, params);

    try {

      result.get().forEachRemaining(record -> {

        Node node = record.get(0).asNode();

        HGNode hgNode = nodes.get(new Long(node.id()));

        // set the labels and properties
        ((ExtendedGraphDbNodeSourceImpl) hgNode.getNodeSource()).getTrait().setLabels(node);
        ((ExtendedGraphDbNodeSourceImpl) hgNode.getNodeSource()).getTrait().setProperties(node);

      });

    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (ExecutionException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public IBoltClient getBoltClient() {

    //
    GraphDbRootNodeSource rootNodeSource = (GraphDbRootNodeSource) this._nodeSource.getNode().getRootNode()
        .getNodeSource();

    //
    IBoltClient boltClient = rootNodeSource.getBoldClient();
    checkNotNull(boltClient, "No bolt client set.");
    return boltClient;
  }

  public boolean isAutoExpand() {
    // TODO
    return true;
  }
}
