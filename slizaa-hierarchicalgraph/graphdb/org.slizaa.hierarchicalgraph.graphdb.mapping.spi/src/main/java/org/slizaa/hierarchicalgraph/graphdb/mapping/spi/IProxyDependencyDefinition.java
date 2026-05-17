/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Function;

public interface IProxyDependencyDefinition extends IDependencyDefinition {

    public Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> getResolveFunction();
}