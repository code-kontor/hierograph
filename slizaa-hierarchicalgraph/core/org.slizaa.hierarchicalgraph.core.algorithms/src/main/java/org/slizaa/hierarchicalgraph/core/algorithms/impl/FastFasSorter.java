package org.slizaa.hierarchicalgraph.core.algorithms.impl;

import java.util.ArrayList;
import java.util.List;

import org.slizaa.hierarchicalgraph.core.algorithms.GraphUtils;
import org.slizaa.hierarchicalgraph.core.algorithms.INodeSorter;
import org.slizaa.hierarchicalgraph.core.model.AbstractHGDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

public class FastFasSorter implements INodeSorter {

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public INodeSorter.SortResult sort(List<HGNode> artifacts) {

        // we have to compute the adjacency matrix first
        int[][] adjacencyMatrix = GraphUtils.computeAdjacencyMatrix(artifacts);

        // the ordered sequence (highest first!)
        FastFAS fastFAS = new FastFAS(adjacencyMatrix);
        int[] ordered = fastFAS.getOrderedSequence();

        // Bubbles
        for (int outerIndex = 1; outerIndex < ordered.length; outerIndex++) {
            for (int index = outerIndex; index >= 1; index--) {

                //
                if (adjacencyMatrix[ordered[index]][ordered[index
                        - 1]] > adjacencyMatrix[ordered[index - 1]][ordered[index]]) {

                    // swap...
                    int temp = ordered[index];
                    ordered[index] = ordered[index - 1];
                    ordered[index - 1] = temp;

                }
                else {

                    // stop bubbling...
                    break;
                }
            }
        }

        // reverse it
        ordered = FastFAS.reverse(ordered);

        // create the result nodes list
        List<HGNode> resultNodes = new ArrayList<>(artifacts.size());
        for (int index : ordered) {
            resultNodes.add(artifacts.get(index));
        }

        // create the list of upwards dependencies
        List<AbstractHGDependency> upwardsDependencies = new ArrayList<>();
        for (Integer[] values : fastFAS.getSkippedEdge()) {
            HGNode source = artifacts.get(values[0]);
            HGNode target = artifacts.get(values[1]);
            upwardsDependencies.add(source.getOutgoingDependenciesTo(target));
        }

        // return the result
        return new SortResult() {

            @Override
            public List getOrderedNodes() {
                return resultNodes;
            }

            @Override
            public List getUpwardDependencies() {
                return upwardsDependencies;
            }
        };
    }
}
