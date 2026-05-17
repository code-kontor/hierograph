package org.slizaa.hierarchicalgraph.core.testfwk.mapstruct;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphPackage;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedGraph;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedTestGraphProviderRule;

import java.util.Arrays;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

public class ParentChildTest {

  @ClassRule
  public static XmiBasedTestGraphProviderRule _graphProvider = new XmiBasedTestGraphProviderRule(XmiBasedGraph.MAP_STRUCT);

    @Test
  public void testParentChild() {
    EcoreUtil.getAllContents(_graphProvider.rootNode(), false).forEachRemaining((c) -> {
      if (HierarchicalgraphPackage.eINSTANCE.getHGNode().isInstance(c)) {
        HGNode node = (HGNode) c;
        checkParentChild(node);
        if (node.getParent() != null) {
          checkBloodline(node, node.getParent());
        }
      }
    });
  }

    private void checkParentChild(HGNode node) {

    // check parent
    if (node.getParent() != null) {
      assertThat(node.getParent().getChildren()).contains(node);
    }

    // check children
    for (HGNode child : node.getChildren()) {
      assertThat(child.getParent()).isEqualTo(node);
    }
  }

    private void checkBloodline(HGNode node, HGNode predecessor) {
    if (predecessor != null) {
      assertThat(predecessor.isPredecessorOf(node));
      assertThat(!predecessor.isSuccessorOf(node));
      assertThat(!node.isPredecessorOf(predecessor));
      assertThat(node.isSuccessorOf(node));
      checkBloodline(node, predecessor.getParent());
    }
  }

    @Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] { {  } });
  }
}
