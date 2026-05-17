package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Map;

import org.slizaa.hierarchicalgraph.core.model.spi.INodeComparator;

public interface IMappingProvider {

    public class DefaultMappingProvider implements IMappingProvider {

    private IMappingProviderMetadata _metaData;

    private IHierarchyDefinitionProvider       _hierarchyProvider;

    private IDependencyDefinitionProvider      _dependencyProvider;

    private ILabelDefinitionProvider _labelProvider;

    private INodeComparator          _nodeComparator;

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

    /**
     * {@inheritDoc}
     */
    @Override
    public INodeMetadataProvider getNodeMetadataProvider() {
      return this._nodeMetadataProvider;
    }
  }

    public interface IMappingProviderMetadata {

        String getIdentifier();

        String getName();

        String getDescription();

        String[] getCategories();

    /**
     * <p>
     * </p>
     *
     * @param category
     * @return
     */
    String getCategoryValue(String category);

    /**
     * <p>
     * </p>
     *
     * @param identifier
     *          has to be set
     * @param name
     *          has to be set
     * @param description
     *          may null
     * @param categories
     *          may null
     * @return
     */
    public static IMappingProviderMetadata createMetadata(String identifier, String name, String description,
        Map<String, String> categories) {

      return new DefaultMappingProviderMetadata(checkNotNull(identifier), checkNotNull(name), description, categories);
    }
  }

    IMappingProviderMetadata getMappingProviderMetadata();

    IHierarchyDefinitionProvider getHierarchyDefinitionProvider();

    IDependencyDefinitionProvider getDependencyDefinitionProvider();

    ILabelDefinitionProvider getLabelDefinitionProvider();

    INodeComparator getNodeComparator();

    INodeMetadataProvider getNodeMetadataProvider();
}
