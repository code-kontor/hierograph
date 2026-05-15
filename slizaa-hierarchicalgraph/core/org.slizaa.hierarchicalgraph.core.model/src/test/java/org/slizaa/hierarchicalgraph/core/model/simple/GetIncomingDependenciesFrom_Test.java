package org.slizaa.hierarchicalgraph.core.model.simple;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class GetIncomingDependenciesFrom_Test {

  /** - */
  @Rule
  public SimpleTestModelRule _model = new SimpleTestModelRule();

  @Test
  public void testGetIncomingDependenciesFrom() {

    //
    HGAggregatedDependency aggregatedDependency = _model.b1().getIncomingDependenciesFrom(_model.a1());
    assertThat(aggregatedDependency).isNotNull();

    assertThat(aggregatedDependency.getAggregatedWeight()).isEqualTo(4);
    assertThat(aggregatedDependency.getCoreDependencies()).containsExactly(_model.a1_b1_core1(), _model.a1_b1_core2(),
        _model.a2_b2_core1(), _model.a3_b3_core1());

    //
    aggregatedDependency = _model.b2().getIncomingDependenciesFrom(_model.a2());
    assertThat(aggregatedDependency).isNotNull();

    assertThat(aggregatedDependency.getAggregatedWeight()).isEqualTo(2);
    assertThat(aggregatedDependency.getCoreDependencies()).containsExactly(_model.a2_b2_core1(),
        _model.a3_b3_core1());

    //
    aggregatedDependency = _model.b3().getIncomingDependenciesFrom(_model.a3());
    assertThat(aggregatedDependency).isNotNull();
    assertThat(aggregatedDependency.getAggregatedWeight()).isEqualTo(1);
    assertThat(aggregatedDependency.getCoreDependencies()).containsExactly(_model.a3_b3_core1());
  }
}
