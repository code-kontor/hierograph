package org.slizaa.hierarchicalgraph.core.selections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
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

public class XRefStack_SetSelectedLeftsidedNodes_Test {

  @ClassRule
  public static XmiBasedTestGraphProviderRule _graphProvider = new XmiBasedTestGraphProviderRule(XmiBasedGraph.EUREKA_AGGREGATED);

  @ClassRule
  public static XRefStackProbeRule    _probe         = new XRefStackProbeRule();
  
  @Rule
  public MockitoRule                        rule           = MockitoJUnit.rule();

  @Mock
  private IXRefListener               _listener;

  @Test
  public void test() {

    //
    _probe.xRefStack().pruneDependenciesForUncroppedCenterNodes(Collections.singletonList(_graphProvider.rootNode()),
        _graphProvider.rootNode().getAccumulatedIncomingCoreDependencies(),
        _graphProvider.rootNode().getAccumulatedOutgoingCoreDependencies());

    //
    assertThat(_probe.xRefStack().getSelectedDependencies()).isEmpty();

    // select "/WEB-INF/lib/archaius-core-0.7.3.jar (54411)"
    _probe.xRefStack().setSelectedCenterNodes(_graphProvider.node(54411));

    //
    assertThat(_probe.xRefStack().getSelectedDependencies()).isEmpty();

    //
    long[] ids = new long[] { 6303, 44027, 54411, 23 };
    int[] sizes = new int[] { 12, 9, 257, 22 };

    _probe.xRefStack().addXRefListener(_listener);

    for (int i = 0; i < sizes.length; i++) {
      _probe.xRefStack().setSelectedLeftsidedNodes(_graphProvider.node(ids[i]));
      assertThat(_probe.xRefStack().getSelectedDependencies()).hasSize(sizes[i]);
    }

    verify(_listener, times(4)).leftsidedNodeSelectionChanged();
  }
}
