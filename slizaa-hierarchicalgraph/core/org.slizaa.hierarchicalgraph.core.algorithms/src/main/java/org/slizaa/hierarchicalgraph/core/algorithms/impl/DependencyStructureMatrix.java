package org.slizaa.hierarchicalgraph.core.algorithms.impl;

import org.slizaa.hierarchicalgraph.core.algorithms.GraphUtils;
import org.slizaa.hierarchicalgraph.core.algorithms.IDependencyStructureMatrix;
import org.slizaa.hierarchicalgraph.core.algorithms.INodeSorter;
import org.slizaa.hierarchicalgraph.core.model.AbstractHGDependency;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

import java.util.*;
import java.util.stream.Collectors;


public class DependencyStructureMatrix implements IDependencyStructureMatrix {

    private List<List<HGNode>> cycles;

    private List<HGNode> nodes;

    private List<AbstractHGDependency> upwardDependencies;

    public DependencyStructureMatrix(Collection<HGNode> nodes) {
        initialize(nodes);
    }

    @Override
    public List<AbstractHGDependency> getUpwardDependencies() {
        return upwardDependencies;
    }

    @Override
    public int getWeight(int i, int j) {

        if (i < 0 || i >= nodes.size() || j < 0 || j >= nodes.size()) {
            return -1;
        }

        HGAggregatedDependency dependency = nodes.get(i).getOutgoingDependenciesTo(nodes.get(j));

        return dependency != null ? dependency.getAggregatedWeight() : 0;
    }

    @Override
    public List<HGNode> getOrderedNodes() {
        return nodes;
    }

    @Override
    public boolean isRowInCycle(int i) {
        return isCellInCycle(i, i);
    }

    @Override
    public boolean isCellInCycle(int i, int j) {

        if (i < 0 || i >= nodes.size() || j < 0 || j >= nodes.size()) {
            return false;
        }

        for (List<HGNode> cycle : cycles) {
            if (cycle.size() > 1 && cycle.contains(nodes.get(i)) && cycle.contains(nodes.get(j))) {
                return true;
            }
        }

        return false;
    }

    @Override
    public List<List<HGNode>> getCycles() {
        return cycles;
    }
    
    @Override
	public int[][] getMatrix() {
    	
    	//
    	int[][] result = new int[nodes.size()][nodes.size()];
    	
    	//
    	for (int i = 0; i < nodes.size(); i++) {
        	for (int j = 0; j < nodes.size(); j++) {
        		HGAggregatedDependency dependency = nodes.get(i).getOutgoingDependenciesTo(nodes.get(j));
        		result[i][j] = dependency != null ? dependency.getAggregatedWeight() : 0;
    		}
		}
    	
    	//
    	return result;
	}

	private void initialize(Collection<HGNode> unorderedArtifacts) {
        Objects.requireNonNull(unorderedArtifacts);

        upwardDependencies = new ArrayList<>();

        List<List<HGNode>> c = GraphUtils.detectStronglyConnectedComponents(unorderedArtifacts);
        INodeSorter artifactSorter = new FastFasSorter();
        for (List<HGNode> cycle : c) {
            INodeSorter.SortResult sortResult = artifactSorter.sort(cycle);
            cycle.clear();
            cycle.addAll(sortResult.getOrderedNodes());
            upwardDependencies.addAll(sortResult.getUpwardDependencies());
        }

        List<HGNode> orderedArtifacts = new ArrayList<>();

        // optimize: un-cycled artifacts without dependencies first
        for (List<HGNode> artifactList : c) {
            if (artifactList.size() == 1 && artifactList.get(0).getOutgoingCoreDependencies().isEmpty()) {
                orderedArtifacts.add(artifactList.get(0));
            }
        }

        for (List<HGNode> cycle : c) {
            for (HGNode node : cycle) {
                if (!orderedArtifacts.contains(node)) {
                    orderedArtifacts.add(node);
                }
            }
        }
        Collections.reverse(orderedArtifacts);
        nodes = orderedArtifacts;

        //
        cycles = c.stream().filter(nodeList -> nodeList.size() > 1).collect(Collectors.toList());
    }
}
