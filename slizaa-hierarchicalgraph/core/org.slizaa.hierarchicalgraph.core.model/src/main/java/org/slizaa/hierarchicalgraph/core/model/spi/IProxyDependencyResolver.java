package org.slizaa.hierarchicalgraph.core.model.spi;

import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public interface IProxyDependencyResolver {

  /**
   * <p>
   * </p>
   *
   * @param dependencyToResolve
   */
  IProxyDependencyResolverJob resolveProxyDependency(HGProxyDependency dependencyToResolve);

  /**
   * <p>
   * </p>
   *
   * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
   *
   */
  public static interface IProxyDependencyResolverJob {

    /**
     * <p>
     * </p>
     *
     */
    void waitForCompletion();
  }
}
