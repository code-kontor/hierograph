/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.internal;

import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.DefaultDependencyDefinition;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinition;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IProxyDependencyDefinition;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

public class ProxyDependencyDefinitionImpl extends DefaultDependencyDefinition implements IProxyDependencyDefinition {

  private Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> _function;

  public ProxyDependencyDefinitionImpl(long idStart, long idTarget, long idRel, String type, int weight,
      Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> function) {
    super(idStart, idTarget, idRel, type, weight);

    this._function = checkNotNull(function);
  }


  @Override
  public Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> getResolveFunction() {
    return this._function;
  }
}
