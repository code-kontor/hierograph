package org.slizaa.hierarchicalgraph.graphdb.testfwk.mapping;

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.DefaultMappingProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.DefaultNodeMetadataProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 *
 */
public class SimpleJTypeMappingProvider extends DefaultMappingProvider implements IMappingProvider {

  /**
   * <p>
   * Creates a new instance of type {@link SimpleJTypeMappingProvider}.
   * </p>
   */
  public SimpleJTypeMappingProvider() {

    super(IMappingProviderMetadata.createMetadata("test", "test", null, null), new SimpleJTypeHierarchyProvider(),
        new SimpleJTypeDependencyProvider(), new SimpleJTypeLabelProvider(), new SimpleJTypeNodeComparator(),
        new DefaultNodeMetadataProvider());
  }
}
