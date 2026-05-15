/**
 *
 */
package org.slizaa.core.boltclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;

import org.junit.*;
import org.neo4j.driver.EagerResult;
import org.neo4j.driver.types.Node;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 *
 */
@Ignore
public class BoltClientTest {

  /** - */
  private static Neo4j neo4j;

  /** - */
  private IBoltClient     _boltClient;

  @BeforeClass
  public static void startNeo4j() {
    neo4j = Neo4jBuilders.newInProcessBuilder().build();
  }

  @AfterClass
  public static void stopNeo4j() {
    if (neo4j != null) {
      neo4j.close();
    }
  }

  /**
   * <p>
   * </p>
   */
  @Before
  public void init() {

    //
    IBoltClientFactory boltClientFactory = IBoltClientFactory.newInstance(Executors.newFixedThreadPool(20));
    this._boltClient = boltClientFactory.createBoltClient(neo4j.boltURI().toString(), "", "");
    this._boltClient.connect();

    //
    this._boltClient.syncExecCypherQuery("CREATE (n:Person { name: 'Andres', title: 'Developer' })");
  }

  /**
   * <p>
   * </p>
   */
  @After
  public void dispose() {

    //
    this._boltClient.disconnect();
  }

  /**
   * <p>
   * </p>
   *
   * @throws Exception
   */
  @Test
  public void testGetNode() throws Exception {

    Node node = this._boltClient.getNode(0);

    assertThat(node).isNotNull();
  }

  @Test
  public void testGson() throws Exception {

    EagerResult result = this._boltClient.syncExecCypherQuery("MATCH (n) return n");

    //
    assertThat(StatementResultToJsonConverter.convertToJson(result)).isEqualTo(
        "[{\"n\":{\"id\":0,\"labels\":[\"Person\"],\"properties\":{\"name\":\"Andres\",\"title\":\"Developer\"},\"__type\":\"NODE\"}}]");
  }
}
