package org.slizaa.hierarchicalgraph.core.model.simple;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class GetOutgoingDependencies_Test {

  /** - */
  @Rule
  public SimpleTestModelRule _model = new SimpleTestModelRule();

  /**
   * <p>
   * </p>
   */
  @Test
  public void testGetOutgoingDependencies() {

    //
    assertThat(_model.a1().getOutgoingCoreDependencies()).hasSize(2).containsOnly(_model.a1_b1_core1(),
        _model.a1_b1_core2());

    assertThat(_model.a1().getAccumulatedOutgoingCoreDependencies()).hasSize(4).containsOnly(_model.a1_b1_core1(),
        _model.a1_b1_core2(), _model.a2_b2_core1(), _model.a3_b3_core1());

    //
    assertThat(_model.a2().getOutgoingCoreDependencies()).hasSize(1).containsOnly(_model.a2_b2_core1());

    assertThat(_model.a2().getAccumulatedOutgoingCoreDependencies()).hasSize(2).containsOnly(_model.a2_b2_core1(),
        _model.a3_b3_core1());

    //
    assertThat(_model.a3().getOutgoingCoreDependencies()).hasSize(1).containsOnly(_model.a3_b3_core1());

    assertThat(_model.a3().getAccumulatedOutgoingCoreDependencies()).hasSize(1).containsOnly(_model.a3_b3_core1());
  }
}
