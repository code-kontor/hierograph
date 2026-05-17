package org.slizaa.hierarchicalgraph.core.testfwk;

import org.junit.ClassRule;
import org.junit.Test;

public class TestGraphProvider_MAP_STRUCT_Test {

  @ClassRule
  public static XmiBasedTestGraphProviderRule gp = new XmiBasedTestGraphProviderRule(XmiBasedGraph.MAP_STRUCT);

  @Test
  public void testOutgoingCoreDependencies() {

    HGNodeUtils.dumpChildren(gp.rootNode());
  }
}
