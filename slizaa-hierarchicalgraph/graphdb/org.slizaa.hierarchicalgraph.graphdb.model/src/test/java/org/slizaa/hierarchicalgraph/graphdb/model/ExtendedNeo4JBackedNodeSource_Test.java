package org.slizaa.hierarchicalgraph.graphdb.model;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.junit.*;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.graphdb.testfwk.GraphDatabaseSetupRule;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactoryFunctions.createNewNode;
import static org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactoryFunctions.createNewRootNode;

@Ignore("Test database is in Neo4j 3.x format, incompatible with Neo4j 5.x")
public class ExtendedNeo4JBackedNodeSource_Test {

  {
    CustomFactoryStandaloneSupport.registerCustomHierarchicalgraphFactory();
  }

  @ClassRule
  public static GraphDatabaseSetupRule graphDatabaseSetup = new GraphDatabaseSetupRule("/mapstruct_1-2-0-Final-db.zip");

  private HGRootNode                        _rootNode;

  private HGNode                            _node;

  @Before
  public void init() throws Exception {

    _rootNode = createNewRootNode(() -> {

      // create the
      GraphDbRootNodeSource result = GraphDbHierarchicalgraphFactory.eINSTANCE.createGraphDbRootNodeSource();

      // set the repository
      result.setBoldClient(graphDatabaseSetup.getBoltClient());

      // return the result
      return result;
    });

    _node = createNewNode(_rootNode, _rootNode, () -> {

      // create the
      GraphDbNodeSource nodeSource = GraphDbHierarchicalgraphFactory.eINSTANCE.createGraphDbNodeSource();

      try {

        // set the repository
        nodeSource.setIdentifier(NodeIdFinder.getDoGetMapperMethod(graphDatabaseSetup.getBoltClient()));

      } catch (Exception e) {
        e.printStackTrace();
        Assert.fail();
      }

      // return the result
      return nodeSource;
    });
  }

    @Test
  public void testGetProperties() throws InterruptedException, ExecutionException {

    EMap<String, String> properties = ((GraphDbNodeSource) _node.getNodeSource()).getProperties();

    assertThat(properties).isNotNull();
    assertThat(properties).hasSize(5);
    assertThat(properties.get("fqn"))
        .isEqualTo("java.lang.Object org.mapstruct.factory.Mappers.doGetMapper(java.lang.Class,java.lang.ClassLoader)");
    assertThat(properties.get("visibility")).isEqualTo("private");
    assertThat(properties.get("name")).isEqualTo("doGetMapper");
    assertThat(properties.get("static")).isEqualTo("true");
    assertThat(properties.get("signature"))
        .isEqualTo("<T:Ljava/lang/Object;>(Ljava/lang/Class<TT;>;Ljava/lang/ClassLoader;)TT;");
  }

    @Test
  public void testGetLabels() {

    EList<String> labels = ((GraphDbNodeSource) _node.getNodeSource()).getLabels();

    assertThat(labels).isNotNull();
    assertThat(labels).hasSize(1);
    assertThat(labels).contains("Method");
  }

  private static int findFreePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }
}
