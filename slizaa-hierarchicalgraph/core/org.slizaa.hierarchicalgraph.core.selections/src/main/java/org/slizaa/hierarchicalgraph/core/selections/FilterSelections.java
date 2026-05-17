package org.slizaa.hierarchicalgraph.core.selections;

import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;

import java.util.List;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

public class FilterSelections {

  public static final String MAIN_NODE_FILTER = "org.slizaa.hierarchicalgraph.selection.MAIN_NODE_FILTER";

    public static final void resteFilter(HGRootNode rootNode) {

    SelectionHolder<NodeSelection> nodeSelection = FilterSelections
        .getOrCreateFilteredNodeSelectionHolder(checkNotNull(rootNode));

    nodeSelection.getSelection().getNodes().clear();
  }

  /**
   * <p>
   * </p>
   *
   * @param rootNode
   * @return
   */
  public static final SelectionHolder<NodeSelection> getOrCreateFilteredNodeSelectionHolder(HGRootNode rootNode) {

    if (!checkNotNull(rootNode).hasExtension(MAIN_NODE_FILTER, SelectionHolder.class)) {

      SelectionHolder<NodeSelection> selectionHolder = SelectionsFactory.eINSTANCE.createSelectionHolder();

      NodeSelection nodeSelection = SelectionsFactory.eINSTANCE.createNodeSelection();
      selectionHolder.setSelection(nodeSelection);

      rootNode.registerExtension(MAIN_NODE_FILTER, selectionHolder);
    }

    return (SelectionHolder<NodeSelection>) rootNode.getExtension(MAIN_NODE_FILTER, SelectionHolder.class);
  }

    public static final void setFilteredNodeIds(HGRootNode rootNode, List<Long> filteredNodeIds) {

    List<HGNode> filteredNodes = filteredNodeIds.stream().map(id -> rootNode.lookupNode(id)).filter(n -> n != null)
        .collect(Collectors.toList());

    SelectionHolder<NodeSelection> selectionHolder = getOrCreateFilteredNodeSelectionHolder(rootNode);

    NodeSelection sel = SelectionsFactory.eINSTANCE.createNodeSelection();
    sel.getNodes().addAll(filteredNodes);
    selectionHolder.setSelection(sel);
  }
  
    public static final void setFilteredNodes(HGRootNode rootNode, List<HGNode> filteredNodes) {

    SelectionHolder<NodeSelection> selectionHolder = getOrCreateFilteredNodeSelectionHolder(rootNode);

    NodeSelection sel = SelectionsFactory.eINSTANCE.createNodeSelection();
    sel.getNodes().addAll(filteredNodes);
    selectionHolder.setSelection(sel);
  }
}
