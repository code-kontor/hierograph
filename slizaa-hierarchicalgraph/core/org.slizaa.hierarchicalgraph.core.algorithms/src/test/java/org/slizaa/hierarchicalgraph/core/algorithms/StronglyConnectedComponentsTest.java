package org.slizaa.hierarchicalgraph.core.algorithms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

/**
 * 
 * @author Gerd W&uuml;therich (gw@code-kontor.io)
 */
public class StronglyConnectedComponentsTest extends AbstractAlgorithmTest {

    @Test
    public void detectSCCs() {

		//
		List<HGNode> nodes = _graphProvider.node(577L).getChildren();

        //
        List<List<HGNode>> stronglyConnectedComponents = GraphUtils.detectStronglyConnectedComponents(nodes);

        //
        assertThat(stronglyConnectedComponents).hasSize(25);

		// @formatter:off
        for (List<HGNode> scc : stronglyConnectedComponents) {
            if (scc.size() == 2) {
            	assertThat(scc).hasSize(10);
                assertThat(scc)
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
            }
        }
		// @formatter:on
    }
}
