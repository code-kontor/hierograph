package org.slizaa.jqassistant.hierarchicalgraph;

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider.DefaultMappingProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.annotations.SlizaaMappingProvider;

@SlizaaMappingProvider
public class JQAssistant_MappingProvider extends DefaultMappingProvider {

	public JQAssistant_MappingProvider() {
		super(IMappingProviderMetadata.createMetadata("org.slizaa.jqassistant.hierarchicalgraph",
				"Slizaa jQAssistant (hierarchical packages)", null, null),
				new JQAssistant_HierarchyProvider(), new JQAssistant_DependencyProvider(),
				new JQAssistant_LabelProvider(), new JQAssistant_NodeComparator(),
				new JQAssistant_NodeMetadataProvider());
	}
}
