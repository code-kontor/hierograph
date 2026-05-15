package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import java.util.List;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public interface IHierarchyDefinitionProvider {

  /**
   * <p>
   * </p>
   *
   * @return
   */
  List<Long> getToplevelNodeIds() throws Exception;

  /**
   * <p>
   * </p>
   *
   * @return
   */
  List<Long[]> getParentChildNodeIds() throws Exception;
}
