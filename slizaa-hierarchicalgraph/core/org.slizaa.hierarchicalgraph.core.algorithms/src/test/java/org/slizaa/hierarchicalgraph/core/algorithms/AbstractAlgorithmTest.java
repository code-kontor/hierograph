package org.slizaa.hierarchicalgraph.core.algorithms;

import org.junit.ClassRule;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedGraph;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedTestGraphProviderRule;

/**
 * @author Gerd W&uuml;therich (gw@code-kontor.io)
 */
public class AbstractAlgorithmTest {

    @ClassRule
    public static XmiBasedTestGraphProviderRule _graphProvider = new XmiBasedTestGraphProviderRule(XmiBasedGraph.MAP_STRUCT);

    /**
     * 
     * @param id
     * @return
     */
	protected HGNode node(long id) {
		return _graphProvider.node(id);
	}

	/**
	 * 
	 * @param nodeFrom
	 * @param nodeTo
	 * @return
	 */
	protected HGAggregatedDependency dependency(long nodeFrom, long nodeTo) {
		HGNode from = _graphProvider.node(nodeFrom);
		HGNode to = _graphProvider.node(nodeTo);
		return from.getOutgoingDependenciesTo(to);
	}
}
