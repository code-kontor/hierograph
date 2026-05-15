package org.slizaa.hierarchicalgraph.core.algorithms;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slizaa.hierarchicalgraph.core.algorithms.impl.DependencyStructureMatrix;
import org.slizaa.hierarchicalgraph.core.algorithms.impl.FastFasSorter;
import org.slizaa.hierarchicalgraph.core.algorithms.impl.Tarjan;

import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

/**
 *
 * @author Gerd W&uuml;therich (gw@code-kontor.io)
 */
public class GraphUtils {

    /**
     * A directed graph is called strongly connected if there is a path in each direction between each pair of vertices
     * of the graph. A strongly connected component (SCC) of a directed graph is a maximal strongly connected subgraph.
     *
     * @param nodes the collection of nodes (the directed graph)
     * @return a list of strongly connected components (SCCs). Note that the result also contains components that
     *         contain just a single node. If you want to detect 'real' cycle (size > 1) please use {@link GraphUtils#detectCycles(Collection)}.
     */
    public static List<List<HGNode>> detectStronglyConnectedComponents(Collection<HGNode> nodes) {
        return new Tarjan<HGNode>().detectStronglyConnectedComponents(Objects.requireNonNull(nodes));
    }

    /**
     * Returns all strongly connected subgraphs (size > 1) of the specified graph.
     *
     * @param nodes
     * @return a list of strongly connected components (SCCs) with a size > 1.
     */
    public static List<List<HGNode>> detectCycles(Collection<HGNode> nodes) {
        return new Tarjan<HGNode>().detectStronglyConnectedComponents(nodes).stream().filter(cycle -> cycle.size() > 1)
                .collect(Collectors.toList());
    }

    /**
     * Creates a dependency structure matrix (DSM) for the given graph nodes.
     *
     * @param nodes the collection of nodes
     * @return
     */
    public static IDependencyStructureMatrix createDependencyStructureMatrix(
            Collection<HGNode> nodes) {
        return new DependencyStructureMatrix(nodes);
    }

    /**
     * An adjacency matrix is a square matrix used to represent a finite graph. The elements of the matrix
     * indicate whether pairs of vertices are connected (adjacent) or not in the graph.
     *
     * @param nodes the collection of nodes
     * @return the adjacency matrix for the given list of nodes
     */
    public static int[][] computeAdjacencyMatrix(List<HGNode> nodes) {
        Objects.requireNonNull(nodes);
        return computeAdjacencyMatrix(nodes.toArray(new HGNode[nodes.size()]));
    }

    /**
     * An adjacency matrix is a square matrix used to represent a finite graph. The elements of the matrix
     * indicate whether pairs of vertices are connected (adjacent) or not in the graph.
     *
     * @param nodes the array of nodes
     * @return the adjacency matrix for the given list of nodes
     */
    public static int[][] computeAdjacencyMatrix(HGNode... nodes) {
        int[][] result = new int[nodes.length][nodes.length];
        for (int i = 0; i < result.length; i++) {
            for (int j = 0; j < result.length; j++) {
                HGAggregatedDependency dependency = nodes[i].getOutgoingDependenciesTo(nodes[j]);
                result[i][j] = dependency != null ? dependency.getAggregatedWeight() : 0;
            }
        }
        return result;
    }

    /**
     * An adjacency list is a collection of (unordered) lists used to represent a finite graph. Each list
     * describes the set of neighbors of a node.
     *
     * @param nodes the array of nodes
     * @return the adjacency list for the given list of nodes
     */
    public static int[][] computeAdjacencyList(Collection<HGNode> nodes) {
        Objects.requireNonNull(nodes);
        return computeAdjacencyList(nodes.toArray(new HGNode[nodes.size()]));
    }

    /**
     * An adjacency list is a collection of (unordered) lists used to represent a finite graph. Each list
     * describes the set of neighbors of a node.
     *
     * @param nodes the array of nodes
     * @return the adjacency list for the given list of nodes
     */
    public static int[][] computeAdjacencyList(HGNode... nodes) {

        int[][] matrix;

        // prepare
        int i = 0;
        Map<HGNode, Integer> map = new HashMap<HGNode, Integer>();
        for (HGNode iArtifact : nodes) {
            map.put(iArtifact, i);
            i++;
        }
        matrix = new int[nodes.length][];

        for (HGNode node : nodes) {
            Collection<HGAggregatedDependency> dependencies = node.getOutgoingDependenciesTo(Arrays.asList(nodes));
            if (dependencies == null) {
                dependencies = Collections.emptyList();
            }
            int index = map.get(node);
            matrix[index] = new int[dependencies.size()];
            int count = 0;
            for (HGAggregatedDependency dependency : dependencies) {
                matrix[index][count] = map.get(dependency.getTo());
                count++;
            }
        }
        return matrix;
    }

    /**
     * Creates a FastFAS based {@link INodeSorter}.
     *
     * @return a FastFAS based {@link INodeSorter}.
     */
    public static INodeSorter createFasNodeSorter() {
        return new FastFasSorter();
    }
}
