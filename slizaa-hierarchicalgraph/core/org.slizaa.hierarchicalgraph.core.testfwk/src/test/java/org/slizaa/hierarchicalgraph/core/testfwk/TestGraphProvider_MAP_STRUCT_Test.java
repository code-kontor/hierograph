package org.slizaa.hierarchicalgraph.core.testfwk;

import org.junit.ClassRule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.testfwk.HGNodeUtils;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedGraph;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedTestGraphProviderRule;

public class TestGraphProvider_MAP_STRUCT_Test {

  @ClassRule
  public static XmiBasedTestGraphProviderRule gp = new XmiBasedTestGraphProviderRule(XmiBasedGraph.MAP_STRUCT);

  @Test
  public void testOutgoingCoreDependencies() {

    HGNodeUtils.dumpChildren(gp.rootNode());
  }
}
