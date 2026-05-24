package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import java.util.List;

public interface IHierarchyDefinitionProvider {

    List<Long> getToplevelNodeIds() throws Exception;

    List<ParentChildNode> getParentChildNodeIds() throws Exception;
}
