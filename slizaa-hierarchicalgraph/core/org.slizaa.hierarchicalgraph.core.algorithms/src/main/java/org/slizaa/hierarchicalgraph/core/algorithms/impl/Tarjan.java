package org.slizaa.hierarchicalgraph.core.algorithms.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.slizaa.hierarchicalgraph.core.algorithms.GraphUtils;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

public class Tarjan<T extends HGNode> {

    private int _index = 0;
    private final ArrayList<Integer> _stack = new ArrayList<Integer>();
    private final List<List<T>> _stronglyConnectedComponents = new ArrayList<List<T>>();
    private int[] _vlowlink;
    private int[] _vindex;

    private HGNode[] _artifacts;

    public List<List<T>> detectStronglyConnectedComponents(Collection<? extends T> artifacts) {
        Objects.requireNonNull(artifacts);

        _artifacts = artifacts.toArray(new HGNode[0]);
        int[][] adjacencyList = GraphUtils.computeAdjacencyList(_artifacts);
        return executeTarjan(adjacencyList);
    }

    private List<List<T>> executeTarjan(int[][] graph) {
        Objects.requireNonNull(graph);

        _stronglyConnectedComponents.clear();
        _index = 0;
        _stack.clear();
        _vlowlink = new int[graph.length];
        _vindex = new int[graph.length];
        for (int i = 0; i < _vlowlink.length; i++) {
            _vlowlink[i] = -1;
            _vindex[i] = -1;
        }

        for (int i = 0; i < graph.length; i++) {
            if (_vindex[i] == -1) {
                tarjan(i, graph);
            }
        }

        return _stronglyConnectedComponents;
    }

    @SuppressWarnings("unchecked")
    private void tarjan(int v, int[][] graph) {
        Objects.requireNonNull(v);
        Objects.requireNonNull(graph);

        _vindex[v] = _index;
        _vlowlink[v] = _index;

        _index++;
        _stack.add(0, v);
        for (int n : graph[v]) {
            if (_vindex[n] == -1) {
                tarjan(n, graph);
                _vlowlink[v] = Math.min(_vlowlink[v], _vlowlink[n]);
            }
            else if (_stack.contains(n)) {
                _vlowlink[v] = Math.min(_vlowlink[v], _vindex[n]);
            }
        }
        if (_vlowlink[v] == _vindex[v]) {
            int n;
            ArrayList<T> component = new ArrayList<T>();
            do {
                n = _stack.remove(0);
                component.add((T) _artifacts[n]);
            }
            while (n != v);
            _stronglyConnectedComponents.add(component);
        }
    }
}
