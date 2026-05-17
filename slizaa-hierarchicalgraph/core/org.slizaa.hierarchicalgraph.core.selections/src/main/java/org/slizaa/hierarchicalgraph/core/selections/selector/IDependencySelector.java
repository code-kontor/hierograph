package org.slizaa.hierarchicalgraph.core.selections.selector;

import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

import java.util.Collection;
import java.util.Set;

public interface IDependencySelector {

    void addDependencySelectorListener(IDependencySelectorListener listener);

    void removeDependencySelectorListener(IDependencySelectorListener listener);

    void setUnfilteredCoreDependencies(Collection<HGCoreDependency> dependencies);

    void setSelectedSourceNodes(HGNode... selectedNodes);

    void setSelectedSourceNodes(Collection<HGNode> selectedNodes);

    void setSelectedTargetNodes(HGNode... selectedNodes);

    void setSelectedTargetNodes(Collection<HGNode> selectedNodes);

    void unselectNodes();

    Set<HGNode> getSelectedSourceNodes();

    Set<HGNode> getSelectedTargetNodes();

    Set<HGCoreDependency> getUnfilteredCoreDependencies();

    Set<HGNode> getUnfilteredSourceNodes();

    Set<HGNode> getUnfilteredTargetNodes();

    Set<HGCoreDependency> getFilteredCoreDependencies();

    Set<HGNode> getFilteredSourceNodes();

    Set<HGNode> getFilteredTargetNodes();

  /**
   * <p>
   * </p>
   *
   * @param fromNode
   * @return
   */
  Set<HGCoreDependency> getDependenciesForSourceNode(HGNode fromNode);

  /**
   * <p>
   * </p>
   *
   * @param toNode
   * @return
   */
  Set<HGCoreDependency> getDependenciesForTargetNode(HGNode toNode);
}