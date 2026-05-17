package org.slizaa.hierarchicalgraph.graphdb.model;

import org.neo4j.driver.exceptions.NoSuchRecordException;
import org.slizaa.core.boltclient.IBoltClient;

import static com.google.common.base.Preconditions.checkNotNull;

public class NodeIdFinder {

    public static long getDoGetMapperMethod(IBoltClient boltClient) {
    return requestId(boltClient,
        "Match (m:Method {fqn: 'java.lang.Object org.mapstruct.factory.Mappers.doGetMapper(java.lang.Class,java.lang.ClassLoader)'}) Return id(m)");
  }

    public static long getAssignmentClassFile(IBoltClient boltClient) {
    return requestId(boltClient,
        "Match (r:Resource {fqn: 'org/mapstruct/ap/internal/model/common/Assignment.class'}) Return id(r)");
  }

    public static long getSetterWrapperForCollectionsAndMapsWithNullCheckType(IBoltClient boltClient) {
    return requestId(boltClient,
        "Match (t:Type {fqn: 'org.mapstruct.ap.internal.model.assignment.SetterWrapperForCollectionsAndMapsWithNullCheck'}) Return id(t)");
  }

  /**
   * <p>
   * </p>
   *
   * @param boltClient
   * @param cypherQuery
   * @return
   */
  private static long requestId(IBoltClient boltClient, String cypherQuery) {

    try {
      return checkNotNull(boltClient).syncExecCypherQuery(checkNotNull(cypherQuery)).records().get(0).get(0).asLong();
    } catch (NoSuchRecordException e) {
      throw new RuntimeException(e);
    }
  }
}
