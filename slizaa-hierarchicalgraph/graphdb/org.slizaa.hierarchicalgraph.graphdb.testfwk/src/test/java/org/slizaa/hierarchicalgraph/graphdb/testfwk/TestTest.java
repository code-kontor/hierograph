package org.slizaa.hierarchicalgraph.graphdb.testfwk;

import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.neo4j.driver.EagerResult;

import static org.assertj.core.api.Assertions.assertThat;

public class TestTest {

  @ClassRule
  public static GraphDatabaseSetupRule graphDatabaseSetup = new GraphDatabaseSetupRule("/mapstruct_1-2-0-Final-db.zip");

  @Ignore("Test database is in Neo4j 3.x format, incompatible with Neo4j 5.x")
  @Test
  public void testTest() {

    EagerResult result =  graphDatabaseSetup.getBoltClient().syncExecCypherQuery("MATCH (node) RETURN count(node)");

    assertThat(result.records().get(0).get("count(node)").asInt()).isEqualTo(40549);
  }
}
