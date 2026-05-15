package org.slizaa.hierarchicalgraph.core.algorithms;


import org.slizaa.hierarchicalgraph.core.model.AbstractHGDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

import java.util.List;

/**
 * @author Gerd W&uuml;therich (gw@code-kontor.io)
 */
public interface IDependencyStructureMatrix {

    List<HGNode> getOrderedNodes();

    List<AbstractHGDependency> getUpwardDependencies();

    List<List<HGNode>> getCycles();

    boolean isCellInCycle(int i, int j);

    boolean isRowInCycle(int i);

    int getWeight(int i, int j);
    
    int[][] getMatrix();
}
