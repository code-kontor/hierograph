package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import org.slizaa.hierarchicalgraph.core.model.HGNode;

/**
 * <p>
 *
 * </p>
 */
public interface INodeSorter {

  /**
   * <p>
   * </p>
   */
  int compare(HGNode node1, HGNode node2);
}
