package org.slizaa.hierarchicalgraph.core.model.simple.resolvedeps;

import org.junit.Test;

public class NodeResolveOutgoing_Test extends AbstractResolverTest {

  @Test
  public void nodeResolveOutgoing() {
    resolve(() -> {
      model().a1().resolveOutgoingProxyDependencies();
    });
  }
}