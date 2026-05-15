/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.DefaultDependencyDefinition;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinition;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IProxyDependencyDefinition;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class ProxyDependencyDefinitionImpl extends DefaultDependencyDefinition implements IProxyDependencyDefinition {

  /** - */
  private Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> _function;

  /**
   * <p>
   * Creates a new instance of type {@link ProxyDependencyDefinitionImpl}.
   * </p>
   *
   * @param idStart
   * @param idTarget
   * @param idRel
   * @param type
   */
  public ProxyDependencyDefinitionImpl(long idStart, long idTarget, long idRel, String type,
      Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> function) {
    super(idStart, idTarget, idRel, type);

    //
    this._function = checkNotNull(function);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> getResolveFunction() {
    return this._function;
  }
}
