package org.slizaa.hierarchicalgraph.core.model.simple;

import org.junit.Rule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;

import static org.assertj.core.api.Assertions.assertThat;

public class AggregatedDependenciesIdentity_Test {

  @Rule
  public SimpleTestModelRule _model = new SimpleTestModelRule();

    @Test
  public void testAggregatedDependenciesIdentity_1() {

    HGAggregatedDependency aggregatedDependency_1 = _model.a1().getOutgoingDependenciesTo(_model.b1());

    _model.root().invalidateAllCaches();

    HGAggregatedDependency aggregatedDependency_2 = _model.a1().getOutgoingDependenciesTo(_model.b1());

    assertThat(aggregatedDependency_1).isEqualTo(aggregatedDependency_2);
    assertThat(aggregatedDependency_1).isSameAs(aggregatedDependency_2);
  }

    @Test
  public void testAggregatedDependenciesIdentity_2() {

    HGAggregatedDependency aggregatedDependency_1 = _model.a1().getOutgoingDependenciesTo(_model.b1());

    _model.root().invalidateAllCaches();

    HGAggregatedDependency aggregatedDependency_2 = aggregatedDependency_1.getFrom()
        .getOutgoingDependenciesTo(aggregatedDependency_1.getTo());

    assertThat(aggregatedDependency_1).isEqualTo(aggregatedDependency_2);
    assertThat(aggregatedDependency_1).isSameAs(aggregatedDependency_2);
  }
}
