/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.cypher;

import org.slizaa.core.boltclient.IBoltClient;

public interface IBoltClientAware {

  void initialize(IBoltClient boltClient) throws Exception;
}
