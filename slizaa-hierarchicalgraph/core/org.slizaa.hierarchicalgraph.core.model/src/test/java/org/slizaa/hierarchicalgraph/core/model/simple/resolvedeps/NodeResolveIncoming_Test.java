package org.slizaa.hierarchicalgraph.core.model.simple.resolvedeps;

import org.junit.Test;

public class NodeResolveIncoming_Test extends AbstractResolverTest {

  @Test
  public void nodeResolveIncoming() {
    resolve(() -> {
      model().b1().resolveIncomingProxyDependencies();
    });
  }
}