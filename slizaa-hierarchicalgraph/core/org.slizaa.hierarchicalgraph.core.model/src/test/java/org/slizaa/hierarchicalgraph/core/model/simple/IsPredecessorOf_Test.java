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
public class IsPredecessorOf_Test {

  /** - */
  @Rule
  public SimpleTestModelRule _model = new SimpleTestModelRule();

  /**
   * <p>
   * </p>
   */
  @Test
  public void testIsPredecessorOf() {

    //
    assertThat(_model.b1().isPredecessorOf(_model.b1())).isFalse();
    assertThat(_model.b1().isPredecessorOf(_model.b2())).isTrue();
    assertThat(_model.b1().isPredecessorOf(_model.b3())).isTrue();

    assertThat(_model.b2().isPredecessorOf(_model.b1())).isFalse();
    assertThat(_model.b2().isPredecessorOf(_model.b2())).isFalse();
    assertThat(_model.b2().isPredecessorOf(_model.b3())).isTrue();

    assertThat(_model.b3().isPredecessorOf(_model.b1())).isFalse();
    assertThat(_model.b3().isPredecessorOf(_model.b2())).isFalse();
    assertThat(_model.b3().isPredecessorOf(_model.b3())).isFalse();
  }
}
