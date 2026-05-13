package org.slizaa.hierarchicalgraph.graphdb.model.impl;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Optional;

import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.util.EcoreEMap;
import org.neo4j.driver.types.Relationship;
import org.slizaa.core.boltclient.IBoltClient;
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
public class ExtendedGraphDbDependencySourceImpl extends GraphDbDependencySourceImpl {

  @SuppressWarnings("unchecked")
  @Override
  public <T> Optional<T> getUserObject(Class<T> type) {
    return checkNotNull(type).isInstance(getUserObject()) ? Optional.of((T) getUserObject()) : Optional.empty();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public EMap<String, String> getProperties() {

    // lazy load...
    if (this.properties == null) {
      reloadProperties();
    }

    // return the result
    return this.properties;
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public EMap<String, String> reloadProperties() {

    Relationship relationship = getBoltClient().getRelationship((long) getIdentifier());

    // lazy init
    if (this.properties == null) {
      this.properties = new EcoreEMap<String, String>(HierarchicalgraphPackage.Literals.STRING_TO_STRING_MAP,
          StringToStringMapImpl.class, this,
          GraphDbHierarchicalgraphPackage.GRAPH_DB_NODE_SOURCE__PROPERTIES);
    }

    // clear properties first
    this.properties.clear();

    // re-populate
    relationship.asMap().entrySet().forEach((e) -> {
      this.properties.put(e.getKey(), e.getValue().toString());
    });

    // return the result
    return this.properties;
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public IBoltClient getBoltClient() {
    return ((GraphDbRootNodeSource) getDependency().getFrom().getRootNode().getNodeSource()).getBoldClient();
  }
}
