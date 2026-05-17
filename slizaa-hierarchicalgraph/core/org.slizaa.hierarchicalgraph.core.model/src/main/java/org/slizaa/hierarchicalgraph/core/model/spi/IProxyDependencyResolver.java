package org.slizaa.hierarchicalgraph.core.model.spi;

import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;

public interface IProxyDependencyResolver {

    IProxyDependencyResolverJob resolveProxyDependency(HGProxyDependency dependencyToResolve);

    public static interface IProxyDependencyResolverJob {

        void waitForCompletion();
  }
}
