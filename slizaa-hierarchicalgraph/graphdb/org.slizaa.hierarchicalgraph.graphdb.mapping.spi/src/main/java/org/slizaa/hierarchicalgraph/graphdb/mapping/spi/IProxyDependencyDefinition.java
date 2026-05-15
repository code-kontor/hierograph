/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public interface IProxyDependencyDefinition extends IDependencyDefinition {

  /**
   * <p>
   * </p>
   */
  public Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> getResolveFunction();
}