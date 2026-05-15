/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.internal;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class ProxyDependencyQueriesHolder {

  /** - */
  private String[] _proxyDependencyQueries;

  /** - */
  private String[] _detailDependencyQueries;

  /**
   * <p>
   * Creates a new instance of type {@link ProxyDependencyQueriesHolder}.
   * </p>
   *
   * @param proxyDependencyQueries
   * @param detailDependencyQueries
   */
  public ProxyDependencyQueriesHolder(String[] proxyDependencyQueries, String[] detailDependencyQueries) {
    this._proxyDependencyQueries = checkNotNull(proxyDependencyQueries);
    this._detailDependencyQueries = checkNotNull(detailDependencyQueries);
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public String[] proxyDependencyQueries() {
    return this._proxyDependencyQueries;
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public String[] detailDependencyQueries() {
    return this._detailDependencyQueries;
  }
}