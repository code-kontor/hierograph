/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;

public interface IProxyDependencyDefinition extends IDependencyDefinition {

    public Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> getResolveFunction();
}