package org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.internal;

import static com.google.common.base.Preconditions.checkNotNull;

public class ProxyDependencyQueriesHolder {

  private String[] _proxyDependencyQueries;

  private String[] _detailDependencyQueries;

  public ProxyDependencyQueriesHolder(String[] proxyDependencyQueries, String[] detailDependencyQueries) {
    this._proxyDependencyQueries = checkNotNull(proxyDependencyQueries);
    this._detailDependencyQueries = checkNotNull(detailDependencyQueries);
  }

    public String[] proxyDependencyQueries() {
    return this._proxyDependencyQueries;
  }

    public String[] detailDependencyQueries() {
    return this._detailDependencyQueries;
  }
}