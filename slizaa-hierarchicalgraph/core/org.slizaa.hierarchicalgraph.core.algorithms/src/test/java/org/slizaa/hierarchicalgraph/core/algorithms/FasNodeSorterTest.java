package org.slizaa.hierarchicalgraph.core.algorithms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGNode;

public class FasNodeSorterTest extends AbstractAlgorithmTest {

    @Test
    public void sortNodes() {

		//
		List<HGNode> nodes = _graphProvider.node(577L).getChildren();

        //
        INodeSorter nodeSorter = GraphUtils.createFasNodeSorter();

        //
        INodeSorter.SortResult sortResult = nodeSorter.sort(nodes);

		// assert upward dependencies
		// @formatter:off
        assertThat(sortResult.getUpwardDependencies()).hasSize(14)
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
        // @formatter:on
    }
}
