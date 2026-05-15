package org.slizaa.hierarchicalgraph.core.selections;

import static org.mockito.Mockito.verify;

import java.util.Collections;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.slizaa.hierarchicalgraph.core.selections.fwk.XRefStackProbeRule;
import org.slizaa.hierarchicalgraph.core.selections.xref.IXRefListener;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedGraph;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedTestGraphProviderRule;

public class XRefStack_SetSelctedNodes_Test {

  @ClassRule
  public static XmiBasedTestGraphProviderRule _graphProvider = new XmiBasedTestGraphProviderRule(XmiBasedGraph.EUREKA_AGGREGATED);

  @ClassRule
  public static XRefStackProbeRule    _probe         = new XRefStackProbeRule();

  @Rule
  public MockitoRule                  rule           = MockitoJUnit.rule();

  @Mock
  private IXRefListener               _listener;

  @Test
  public void test() {

    //
    _probe.xRefStack().addXRefListener(_listener);

    //
    _probe.xRefStack().pruneDependenciesForUncroppedCenterNodes(Collections.singletonList(_graphProvider.rootNode()),
        _graphProvider.rootNode().getAccumulatedIncomingCoreDependencies(),
        _graphProvider.rootNode().getAccumulatedOutgoingCoreDependencies());

    verify(_listener).coreDependenciesChanged();

    // select "/WEB-INF/lib/archaius-core-0.7.3.jar (54411)"
    _probe.xRefStack().setSelectedCenterNodes(_graphProvider.node(54411));

    verify(_listener).centerNodeSelectionChanged();

    _probe.xRefStack().cropSelection();

    verify(_listener).croppedSelectionChanged();

    _probe.xRefStack().setSelectedLeftsidedNodes(_graphProvider.node(54411));

    verify(_listener).leftsidedNodeSelectionChanged();
  }
}
