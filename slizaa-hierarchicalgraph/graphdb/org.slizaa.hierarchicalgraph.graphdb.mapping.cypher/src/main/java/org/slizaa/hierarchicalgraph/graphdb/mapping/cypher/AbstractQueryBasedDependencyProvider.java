package org.slizaa.hierarchicalgraph.graphdb.mapping.cypher;

import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;
import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.internal.BoltClientQueries;
import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.internal.ProxyDependencyQueriesHolder;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinition;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinitionProvider;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class AbstractQueryBasedDependencyProvider implements IDependencyDefinitionProvider, IBoltClientAware {

  private List<String>                       _simpleDependenciesQueries;

  private List<ProxyDependencyQueriesHolder> _proxyDependenciesQueries;

  private List<IDependencyDefinition>        _dependencies;

  /**
   * <p>
   * Creates a new instance of type {@link AbstractQueryBasedDependencyProvider}.
   * </p>
   */
  public AbstractQueryBasedDependencyProvider() {
    this._simpleDependenciesQueries = new LinkedList<>();
    this._proxyDependenciesQueries = new LinkedList<>();
  }


  @Override
  public void initialize(final IBoltClient boltClient) throws Exception {

    checkNotNull(boltClient);

    initialize();

    this._dependencies = new ArrayList<>();

    // simple dependencies
    for (String query : this._simpleDependenciesQueries) {
      this._dependencies.addAll(BoltClientQueries.resolveDependencyQuery(boltClient, query, null));
    }

    // proxy dependencies
    for (ProxyDependencyQueriesHolder proxyDependenciesDefinition : this._proxyDependenciesQueries) {

      // create the resolver function
      Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> resolverFunction = (proxyDependency) -> {
        return BoltClientQueries.resolveProxyDependency(proxyDependency, proxyDependenciesDefinition, boltClient);
      };

      // resolve the 'top-level' queries
      for (String query : proxyDependenciesDefinition.proxyDependencyQueries()) {
        this._dependencies.addAll(BoltClientQueries.resolveDependencyQuery(boltClient, query, resolverFunction));
      }
    }
  }

    protected abstract void initialize();


  @Override
  public List<IDependencyDefinition> getDependencies() throws Exception {
    return this._dependencies;
  }

  /**
   * <p>
   * </p>
   *
   * @param proxyDependencyQueries
   * @param detailDependencyQueries
   * @return
   */
  protected void addProxyDependencyDefinitions(String[] proxyDependencyQueries, String[] detailDependencyQueries) {

    this._proxyDependenciesQueries.add(
        new ProxyDependencyQueriesHolder(checkNotNull(proxyDependencyQueries), checkNotNull(detailDependencyQueries)));
  }

    protected void addProxyDependencyDefinitions(String proxyDependencyQuery, String[] detailDependencyQueries) {

    this._proxyDependenciesQueries.add(new ProxyDependencyQueriesHolder(
        new String[] { checkNotNull(proxyDependencyQuery) }, checkNotNull(detailDependencyQueries)));
  }

    protected void addSimpleDependencyDefinitions(String simpleDependencyQuery) {

    this._simpleDependenciesQueries.add(simpleDependencyQuery);
  }
}