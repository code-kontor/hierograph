package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import java.util.List;

public interface IHierarchyDefinitionProvider {

    List<RootNode> getToplevelNodeIds() throws Exception;

    List<ParentChildNode> getParentChildNodeIds() throws Exception;

    /**
     * A top-level node in the hierarchy.
     *
     * @param id   the Neo4j node ID
     * @param kind the node kind (e.g. a {@code JavaNodeKind} enum value)
     */
    record RootNode(long id, Object kind) {}

    /**
     * A parent-child pair in the hierarchy.
     *
     * @param parentId  the parent node ID
     * @param childId   the child node ID
     * @param childKind the child's node kind (e.g. a {@code JavaNodeKind} enum value)
     */
    record ParentChildNode(long parentId, long childId, Object childKind) {}
}
