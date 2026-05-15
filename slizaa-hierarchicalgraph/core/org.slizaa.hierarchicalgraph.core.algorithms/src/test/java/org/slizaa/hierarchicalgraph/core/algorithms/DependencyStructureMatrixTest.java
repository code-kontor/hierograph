package org.slizaa.hierarchicalgraph.core.algorithms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

/**
 * 
 * @author Gerd W&uuml;therich (gw@code-kontor.io)
 */
public class DependencyStructureMatrixTest extends AbstractAlgorithmTest {

	@Test
	public void detectCycle() {

		//
		List<HGNode> nodes = _graphProvider.node(577L).getChildren();

		//
		IDependencyStructureMatrix dsm = GraphUtils.createDependencyStructureMatrix(nodes);

		// assert ordered nodes
		assertThat(dsm.getOrderedNodes()).hasSize(34);

		// assert upward dependencies
		// @formatter:off
        assertThat(dsm.getUpwardDependencies()).hasSize(14)
            .containsExactlyInAnyOrder( 
        		dependency(16667, 7676),
        		dependency(7155, 7676),
        		dependency(19564, 7676),
        		dependency(14903, 7676),
        		dependency(20483, 7676),
        		dependency(14576, 7676),
        		dependency(19647, 7676),
        		dependency(20483, 14903),
        		dependency(19647, 14903),
        		dependency(19647, 14576),
        		dependency(19647, 16667),
        		dependency(7155, 20483),
        		dependency(19647, 20483),
        		dependency(7155, 19647)
        		);

        // assert cycles
        assertThat(dsm.getCycles()).hasSize(1);
        assertThat(dsm.getCycles().get(0)).hasSize(10);
        assertThat(dsm.getCycles().get(0))
        	.containsExactlyInAnyOrder(
        		node(19564),
        		node(7155),
        		node(19647),
        		node(19397),
        		node(20483),
        		node(16667),
        		node(14485),
        		node(7676),
        		node(14576),
        		node(14903)
        	);
        // @formatter:on
	}
}
