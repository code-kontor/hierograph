package org.slizaa.hierarchicalgraph.core.model.simple.resolvedeps;

import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;

public class AggregatedDependencyResolve_Test extends AbstractResolverTest {

    @Test
  public void aggregatedDependencyResolve() {

    resolve(() -> {
      HGAggregatedDependency aggregatedDependency = model().a1().getOutgoingDependenciesTo(model().b1());
      aggregatedDependency.resolveProxyDependencies();
    });
  }
}