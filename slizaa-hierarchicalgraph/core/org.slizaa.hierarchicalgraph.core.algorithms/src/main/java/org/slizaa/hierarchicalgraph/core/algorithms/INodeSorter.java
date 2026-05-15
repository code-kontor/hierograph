package org.slizaa.hierarchicalgraph.core.algorithms;

import org.slizaa.hierarchicalgraph.core.model.AbstractHGDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

import java.util.List;

/**
 * @author Gerd W&uuml;therich (gw@code-kontor.io)
 */
public interface INodeSorter {

    SortResult sort(List<HGNode> node);

    public interface SortResult {

        List<HGNode> getOrderedNodes();

        List<AbstractHGDependency> getUpwardDependencies();
    }
}
