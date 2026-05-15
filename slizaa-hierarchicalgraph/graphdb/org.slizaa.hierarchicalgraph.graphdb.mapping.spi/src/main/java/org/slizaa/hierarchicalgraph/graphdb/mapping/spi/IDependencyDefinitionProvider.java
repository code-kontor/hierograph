package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import java.util.List;

public interface IDependencyDefinitionProvider {

  /**
   * <p>
   * </p>
   *
   * @return
   */
  List<IDependencyDefinition> getDependencies() throws Exception;
}
