package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import java.util.List;

public interface IDependencyDefinitionProvider {

    List<IDependencyDefinition> getDependencies() throws Exception;
}
