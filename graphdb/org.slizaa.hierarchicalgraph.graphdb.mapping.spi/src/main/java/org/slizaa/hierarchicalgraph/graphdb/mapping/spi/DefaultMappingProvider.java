package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import static com.google.common.base.Preconditions.checkNotNull;

import org.slizaa.hierarchicalgraph.core.model.spi.INodeComparator;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class DefaultMappingProvider implements IMappingProvider {

  /** - */
  private IMappingProviderMetadata _metaData;

  /** - */
  private IHierarchyDefinitionProvider       _hierarchyProvider;

  /** - */
  private IDependencyDefinitionProvider      _dependencyProvider;

  /** - */
  private ILabelDefinitionProvider _labelProvider;

  /** - */
  private INodeComparator          _nodeComparator;

  /** - */
  private INodeMetadataProvider    _nodeMetadataProvider;

  /**
   * <p>
   * Creates a new instance of type {@link DelegatingMappingProvider}.
   * </p>
   *
   * @param metaInformation
   * @param hierarchyProvider
   * @param dependencyProvider
   * @param labelProvider
   * @param nodeComparator
   * @param nodeMetadataProvider
   */
  public DefaultMappingProvider(IMappingProviderMetadata metaInformation, IHierarchyDefinitionProvider hierarchyProvider,
      IDependencyDefinitionProvider dependencyProvider, ILabelDefinitionProvider labelProvider,
      INodeComparator nodeComparator, INodeMetadataProvider nodeMetadataProvider) {

    this._metaData = checkNotNull(metaInformation);
    this._hierarchyProvider = checkNotNull(hierarchyProvider);
    this._dependencyProvider = checkNotNull(dependencyProvider);
    this._labelProvider = checkNotNull(labelProvider);
    this._nodeComparator = checkNotNull(nodeComparator);
    this._nodeMetadataProvider = checkNotNull(nodeMetadataProvider);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public IMappingProviderMetadata getMappingProviderMetadata() {
    return this._metaData;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public IHierarchyDefinitionProvider getHierarchyDefinitionProvider() {
    return this._hierarchyProvider;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public IDependencyDefinitionProvider getDependencyDefinitionProvider() {
    return this._dependencyProvider;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ILabelDefinitionProvider getLabelDefinitionProvider() {
    return this._labelProvider;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public INodeComparator getNodeComparator() {
    return this._nodeComparator;
  }

  @Override
  public INodeMetadataProvider getNodeMetadataProvider() {
    return this._nodeMetadataProvider;
  }
}
