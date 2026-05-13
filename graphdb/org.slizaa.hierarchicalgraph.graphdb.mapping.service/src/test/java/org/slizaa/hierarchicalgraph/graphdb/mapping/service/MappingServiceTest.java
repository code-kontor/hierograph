package org.slizaa.hierarchicalgraph.graphdb.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.slizaa.core.boltclient.testfwk.BoltClientConnectionRule;
import org.slizaa.core.progressmonitor.DefaultProgressMonitor;
import org.slizaa.hierarchicalgraph.core.algorithms.GraphUtils;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.graphdb.testfwk.GraphDatabaseSetupRule;
import org.slizaa.hierarchicalgraph.graphdb.testfwk.mapping.SimpleJTypeMappingProvider;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
@Ignore("Test database is in Neo4j 3.x format, incompatible with Neo4j 5.x")
public class MappingServiceTest {

  @ClassRule
  public static GraphDatabaseSetupRule graphDatabaseSetup = new GraphDatabaseSetupRule("/mapstruct_1-2-0-Final-db.zip");

  /**
   * <p>
   * </p>
   *
   * @throws ClassNotFoundException
   */
  @Test
  public void testMappingService() throws ClassNotFoundException {

    IMappingService mappingService = MappingFactory.createMappingServiceForStandaloneSetup();

    HGRootNode rootNode = mappingService
        .convert(new SimpleJTypeMappingProvider(), graphDatabaseSetup.getBoltClient(), new DefaultProgressMonitor("Mapping...", 100, DefaultProgressMonitor.consoleLogger()));

    assertThat(rootNode.getChildren()).hasSize(2);

    assertThat(rootNode.getChildren().get(0).getChildren()).hasSize(5);
    assertThat(rootNode.getChildren().get(1).getChildren()).hasSize(36);

    int[][] matrix = GraphUtils.computeAdjacencyMatrix(rootNode.getChildren().get(1).getChildren());
  }
}
