package org.slizaa.hierarchicalgraph.graphdb.model;

import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;

public class GraphTraversalUtil {

  public static void resolveAll(HGRootNode rootNode) {
    resolveNodeSource(rootNode);
    rootNode.getChildren().forEach(GraphTraversalUtil::resolveRecursively);
  }

  private static void resolveRecursively(HGNode node) {
    resolveNodeSource(node);
    node.getChildren().forEach(GraphTraversalUtil::resolveRecursively);
  }

  private static void resolveNodeSource(HGNode node) {
    if (node.getNodeSource() instanceof GraphDbNodeSource) {
      GraphDbNodeSource nodeSource = (GraphDbNodeSource) node.getNodeSource();
      nodeSource.getLabels();
      nodeSource.getProperties();
    }
  }
}
