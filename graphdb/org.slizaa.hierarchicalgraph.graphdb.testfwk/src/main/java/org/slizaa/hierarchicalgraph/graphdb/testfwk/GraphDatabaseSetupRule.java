package org.slizaa.hierarchicalgraph.graphdb.testfwk;

import com.google.common.base.Preconditions;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.harness.junit.rule.Neo4jRule;
import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.core.boltclient.testfwk.BoltClientConnectionRule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 *
 */
public class GraphDatabaseSetupRule implements TestRule {

  //
  private PredefinedDatabaseDirectoryRule _predefinedDatabaseDirectoryRule;

  //
  private Neo4jRule _neo4jRule;

  //
  private BoltClientConnectionRule _boltClientConnection;

  /**
   * @param databaseArchivePath
   */
  public GraphDatabaseSetupRule(String databaseArchivePath) {

    checkNotNull(databaseArchivePath);

    try (InputStream inputStream = GraphDatabaseSetupRule.class.getResourceAsStream(databaseArchivePath)) {

      //
      int freePort = findFreePort();

      _predefinedDatabaseDirectoryRule = new PredefinedDatabaseDirectoryRule(
          GraphDatabaseSetupRule.class.getResourceAsStream(databaseArchivePath));
      _predefinedDatabaseDirectoryRule.getParentDirectory().mkdirs();

      _neo4jRule = new Neo4jRule()
          .withConfig(BoltConnector.enabled, true)
          .withConfig(BoltConnector.listen_address, new SocketAddress("localhost", freePort));

      _boltClientConnection = new BoltClientConnectionRule("localhost", freePort);

    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  /**
   * @param base
   * @param description
   * @return
   */
  @Override
  public Statement apply(final Statement base, final Description description) {

    return new Statement() {

      @Override
      public void evaluate() throws Throwable {

        _predefinedDatabaseDirectoryRule.apply(
            _neo4jRule.apply(
                _boltClientConnection.apply(base, description),
                description),
            description).evaluate();
      }
    };
  }

  /**
   * @return
   */
  public IBoltClient getBoltClient() {
    return _boltClientConnection.getBoltClient();
  }

  /**
   * @return
   */
  private static int findFreePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }
}
