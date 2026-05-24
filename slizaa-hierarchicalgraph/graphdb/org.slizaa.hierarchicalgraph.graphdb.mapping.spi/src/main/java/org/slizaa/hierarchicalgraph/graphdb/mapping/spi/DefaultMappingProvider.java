package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import org.slizaa.hierarchicalgraph.core.model.spi.INodeComparator;

import static com.google.common.base.Preconditions.checkNotNull;

public class DefaultMappingProvider implements IMappingProvider {

  private IMappingProviderMetadata _metaData;

  private IHierarchyDefinitionProvider       _hierarchyProvider;

  private IDependencyDefinitionProvider      _dependencyProvider;

  private INodeMetadataProvider    _nodeMetadataProvider;

  /**
   * <p>
   * Creates a new instance of type {@link DelegatingMappingProvider}.
   * </p>
   *
   * @param metaInformation
   * @param hierarchyProvider
   * @param dependencyProvider
   * @param nodeMetadataProvider
   */
  public DefaultMappingProvider(IMappingProviderMetadata metaInformation, IHierarchyDefinitionProvider hierarchyProvider,
      IDependencyDefinitionProvider dependencyProvider, INodeMetadataProvider nodeMetadataProvider) {

    this._metaData = checkNotNull(metaInformation);
    this._hierarchyProvider = checkNotNull(hierarchyProvider);
    this._dependencyProvider = checkNotNull(dependencyProvider);
     this._nodeMetadataProvider = checkNotNull(nodeMetadataProvider);
  }


  @Override
  public IMappingProviderMetadata getMappingProviderMetadata() {
    return this._metaData;
  }


  @Override
  public IHierarchyDefinitionProvider getHierarchyDefinitionProvider() {
    return this._hierarchyProvider;
  }


  @Override
  public IDependencyDefinitionProvider getDependencyDefinitionProvider() {
    return this._dependencyProvider;
  }

  @Override
  public INodeMetadataProvider getNodeMetadataProvider() {
    return this._nodeMetadataProvider;
  }
}
