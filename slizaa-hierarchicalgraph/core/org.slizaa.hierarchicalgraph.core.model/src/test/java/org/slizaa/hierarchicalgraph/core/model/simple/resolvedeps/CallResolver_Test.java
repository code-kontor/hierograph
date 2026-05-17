package org.slizaa.hierarchicalgraph.core.model.simple.resolvedeps;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.model.simple.SimpleTestModelRule;
import org.slizaa.hierarchicalgraph.core.model.spi.IProxyDependencyResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class CallResolver_Test {

  @Rule
  public SimpleTestModelRule           _model = new SimpleTestModelRule();

  private IProxyDependencyResolver _resolver;

  private HGAggregatedDependency   _aggregatedDependency;

  @Before
  public void before() {

    this._resolver = mock(IProxyDependencyResolver.class);
    this._model.root().registerExtension(IProxyDependencyResolver.class, this._resolver);

    // get the aggregated dependency
    this._aggregatedDependency = this._model.a1().getOutgoingDependenciesTo(this._model.b1());
    assertThat(this._aggregatedDependency).isNotNull();
  }

    @Test
  public void testResolveProxyDependencies() {

    this._aggregatedDependency.resolveProxyDependencies();

    verify(this._resolver).resolveProxyDependency(this._model.a3_b3_core1());
    verifyNoMoreInteractions(this._resolver);

    reset(this._resolver);

    // don't call 'createNewAggregatedDependencyResolver' again
    this._aggregatedDependency.resolveProxyDependencies();

    verify(this._resolver, never()).resolveProxyDependency(any());
  }
}
