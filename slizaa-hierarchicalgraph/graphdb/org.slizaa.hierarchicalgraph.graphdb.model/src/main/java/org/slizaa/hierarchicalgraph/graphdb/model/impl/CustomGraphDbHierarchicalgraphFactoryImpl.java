/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.model.impl;

import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbDependencySource;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbNodeSource;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbRootNodeSource;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 *
 */
public class CustomGraphDbHierarchicalgraphFactoryImpl extends GraphDbHierarchicalgraphFactoryImpl {

  @Override
  public GraphDbNodeSource createGraphDbNodeSource() {
    return new ExtendedGraphDbNodeSourceImpl();
  }

  @Override
  public GraphDbRootNodeSource createGraphDbRootNodeSource() {
    return new ExtendedGraphDbRootNodeSourceImpl();
  }

  @Override
  public GraphDbDependencySource createGraphDbDependencySource() {
    return new ExtendedGraphDbDependencySourceImpl();
  }
}
