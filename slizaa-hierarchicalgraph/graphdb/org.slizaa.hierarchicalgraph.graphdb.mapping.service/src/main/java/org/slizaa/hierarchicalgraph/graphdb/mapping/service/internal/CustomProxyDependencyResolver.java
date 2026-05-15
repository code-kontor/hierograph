package org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal.GraphFactoryFunctions.createDependencies;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;
import org.slizaa.hierarchicalgraph.core.model.spi.IProxyDependencyResolver;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinition;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbDependencySource;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class CustomProxyDependencyResolver implements IProxyDependencyResolver {

  /**
   * {@inheritDoc}
   */
  @Override
  public IProxyDependencyResolverJob resolveProxyDependency(final HGProxyDependency dependency) {
    return new ProxyDependencyResolverJob(checkNotNull(dependency));
  }

  /**
   * <p>
   * </p>
   *
   * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
   */
  private class ProxyDependencyResolverJob implements IProxyDependencyResolverJob {

    /** - */
    private List<Future<List<IDependencyDefinition>>> _futures;

    /** - */
    private HGProxyDependency                         _proxyDependency;

    /**
     * <p>
     * Creates a new instance of type {@link ProxyDependencyResolverJob}.
     * </p>
     */
    public ProxyDependencyResolverJob(HGProxyDependency proxyDependency) {

      //
      this._proxyDependency = checkNotNull(proxyDependency);

      //
      GraphDbDependencySource dependencySource = (GraphDbDependencySource) proxyDependency.getDependencySource();

      // TODO List<Future<List<IDependencyDefinition>>>>
      @SuppressWarnings("unchecked")
      Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> resolveFunction = (Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>>) dependencySource
          .getUserObject();

      //
      this._futures = resolveFunction.apply(proxyDependency);
    }

    @Override
    public void waitForCompletion() {

      //
      List<IDependencyDefinition> resolvedDependencyDefinitions = new ArrayList<>();

      //
      for (Future<List<IDependencyDefinition>> future : this._futures) {
        try {
          resolvedDependencyDefinitions.addAll(future.get());
        } catch (InterruptedException | ExecutionException e) {
          e.printStackTrace();
        }
      }

      //
      List<HGCoreDependency> coreDependencies = createDependencies(resolvedDependencyDefinitions,
          this._proxyDependency.getRootNode(),
          (id, type) -> GraphFactoryFunctions.createDependencySource(id, type, null), false, null);

      //
      this._proxyDependency.getResolvedCoreDependencies().addAll(coreDependencies);
    }
  }
}
