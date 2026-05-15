package org.slizaa.hierarchicalgraph.core.selections;


import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import org.junit.ClassRule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.selections.fwk.XRefStackProbeRule;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedGraph;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedTestGraphProviderRule;

public class XRefStack_BasicSetupWithSelectedNodes_Test {

  @ClassRule
  public static XmiBasedTestGraphProviderRule _graphProvider = new XmiBasedTestGraphProviderRule(XmiBasedGraph.EUREKA_AGGREGATED);

  @ClassRule
  public static XRefStackProbeRule    _probe         = new XRefStackProbeRule();
  
  @Test
  public void test() {

    //
    _probe.xRefStack().pruneDependenciesForUncroppedCenterNodes(Collections.singletonList(_graphProvider.rootNode()), _graphProvider.rootNode().getAccumulatedIncomingCoreDependencies(),
        _graphProvider.rootNode().getAccumulatedOutgoingCoreDependencies());

    _probe.xRefStack().setSelectedCenterNodes(_graphProvider.node(54411));
    
    //
    assertThat(_probe.xRefStack().getCenterNodes()).hasSize(13365);
    assertThat(_probe.xRefStack().getBackreferencedCenterNodes()).hasSize(0);
    assertThat(_probe.xRefStack().getLeftsidedNodes()).hasSize(100);
    assertThat(_probe.xRefStack().getRightsidedNodes()).hasSize(119);
  }
}
