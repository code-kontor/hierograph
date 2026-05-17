package org.slizaa.hierarchicalgraph.core.testfwk;

import org.junit.ClassRule;
import org.junit.Test;

public class TestGraphProvider_EUREKA_Test {

  @ClassRule
  public static XmiBasedTestGraphProviderRule gp = new XmiBasedTestGraphProviderRule(XmiBasedGraph.EUREKA);

  @Test
  public void testOutgoingCoreDependencies() {

    HGNodeUtils.dumpChildren(gp.rootNode());
  }
}
