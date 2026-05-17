package org.slizaa.hierarchicalgraph.core.model.simple;

import org.junit.Rule;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class IsSuccessorOf_Test {

  @Rule
  public SimpleTestModelRule _model = new SimpleTestModelRule();

    @Test
  public void testIsSuccessorOf() {

    assertThat(_model.b1().isSuccessorOf(_model.b1())).isFalse();
    assertThat(_model.b1().isSuccessorOf(_model.b2())).isFalse();
    assertThat(_model.b1().isSuccessorOf(_model.b3())).isFalse();

    assertThat(_model.b2().isSuccessorOf(_model.b1())).isTrue();
    assertThat(_model.b2().isSuccessorOf(_model.b2())).isFalse();
    assertThat(_model.b2().isSuccessorOf(_model.b3())).isFalse();

    assertThat(_model.b3().isSuccessorOf(_model.b1())).isTrue();
    assertThat(_model.b3().isSuccessorOf(_model.b2())).isTrue();
    assertThat(_model.b3().isSuccessorOf(_model.b3())).isFalse();
  }
}
