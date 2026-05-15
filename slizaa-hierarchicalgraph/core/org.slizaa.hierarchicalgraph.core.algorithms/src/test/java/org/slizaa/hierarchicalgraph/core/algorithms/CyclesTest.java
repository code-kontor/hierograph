package org.slizaa.hierarchicalgraph.core.algorithms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

/**
 * @author Gerd W&uuml;therich (gw@code-kontor.io)
 */
public class CyclesTest extends AbstractAlgorithmTest {

	@Test
	public void detectCycle() {

		List<HGNode> nodes = _graphProvider.node(577L).getChildren();

		List<List<HGNode>> cycles = GraphUtils.detectCycles(nodes);

		// @formatter:off
		assertThat(cycles).hasSize(1);
		assertThat(cycles.get(0)).hasSize(10);
		assertThat(cycles.get(0))
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
