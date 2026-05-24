package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

/**
 * A parent-child pair in the hierarchy, carrying the Hierograph kind of the child node.
 *
 * @param parentId  the parent node ID
 * @param childId   the child node ID
 * @param childKind the Hierograph kind of the child node (e.g. {@code "java.class"})
 */
public record ParentChildNode(long parentId, long childId, String childKind) {}
