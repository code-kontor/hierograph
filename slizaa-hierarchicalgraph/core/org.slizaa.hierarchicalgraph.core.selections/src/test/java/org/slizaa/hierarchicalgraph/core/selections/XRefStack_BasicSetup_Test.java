package org.slizaa.hierarchicalgraph.core.selections;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import org.junit.ClassRule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.DefaultNodeSource;
import org.slizaa.hierarchicalgraph.core.selections.fwk.XRefStackProbeRule;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedGraph;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedTestGraphProviderRule;

public class XRefStack_BasicSetup_Test {

  @ClassRule
  public static XmiBasedTestGraphProviderRule _graphProvider = new XmiBasedTestGraphProviderRule(
      XmiBasedGraph.EUREKA_AGGREGATED);

  @ClassRule
  public static XRefStackProbeRule            _probe         = new XRefStackProbeRule();

  @Test
  public void test() {

    //
    _probe.xRefStack().pruneDependenciesForUncroppedCenterNodes(Collections.singletonList(_graphProvider.rootNode()),
        _graphProvider.rootNode().getAccumulatedIncomingCoreDependencies(),
        _graphProvider.rootNode().getAccumulatedOutgoingCoreDependencies());

    //
    assertThat(_probe.xRefStack().getCenterNodes()).hasSize(13365);
    assertThat(_probe.xRefStack().getBackreferencedCenterNodes()).hasSize(0);
    assertThat(_probe.xRefStack().getLeftsidedNodes()).hasSize(11975);
    assertThat(_probe.xRefStack().getRightsidedNodes()).hasSize(12437);

    // assert only dependency sources on the left side
    assertThat(_probe.xRefStack().getLeftsidedNodes()).allMatch(
        node -> node.getNodeSource(DefaultNodeSource.class).get().getProperties().get("labels").contains("Type"));

    // assert only dependency targets on the right side
    assertThat(_probe.xRefStack().getLeftsidedNodes()).allMatch(
        node -> node.getNodeSource(DefaultNodeSource.class).get().getProperties().get("labels").contains("Type"));
  }
}
