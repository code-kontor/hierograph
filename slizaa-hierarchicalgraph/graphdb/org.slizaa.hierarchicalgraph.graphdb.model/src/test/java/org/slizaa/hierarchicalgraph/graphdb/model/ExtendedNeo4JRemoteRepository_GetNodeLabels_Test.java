package org.slizaa.hierarchicalgraph.graphdb.model;

import com.google.common.collect.Lists;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.hierarchicalgraph.graphdb.testfwk.GraphDatabaseSetupRule;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * https://github.com/lukas-krecan/JsonUnit
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
@Ignore("Test database is in Neo4j 3.x format, incompatible with Neo4j 5.x")
@RunWith(value = Parameterized.class)
public class ExtendedNeo4JRemoteRepository_GetNodeLabels_Test {

  {
    CustomFactoryStandaloneSupport.registerCustomHierarchicalgraphFactory();
  }

  @ClassRule
  public static GraphDatabaseSetupRule graphDatabaseSetup = new GraphDatabaseSetupRule("/mapstruct_1-2-0-Final-db.zip");

  private Function<IBoltClient, Long>       _nodeIdProvider;

  private List<String>                      _expectedLabels;

  /**
   * <p>
   * Creates a new instance of type {@link ExtendedNeo4JRemoteRepository_GetNodeLabels_Test}.
   * </p>
   *
   * @param nodeIdProvider
   * @param expectedLabels
   */
  public ExtendedNeo4JRemoteRepository_GetNodeLabels_Test(Function<IBoltClient, Long> nodeIdProvider,
      List<String> expectedLabels) {
    this._nodeIdProvider = checkNotNull(nodeIdProvider);
    this._expectedLabels = checkNotNull(expectedLabels);
  }

    @Test
  public void getNodeProperties() {

    IBoltClient boltClient = graphDatabaseSetup.getBoltClient();

    List<String> labels = Lists.newArrayList(boltClient.getNode(_nodeIdProvider.apply(boltClient)).labels());
    assertThat(labels).containsExactlyElementsOf(_expectedLabels);
  }

    @Parameters(name = "{index}: getNodeLabels({0}) = {1}")
  public static Collection<Object[]> data() {

    Function<IBoltClient, Long> f1 = c -> NodeIdFinder.getAssignmentClassFile(c);
    Function<IBoltClient, Long> f2 = c -> NodeIdFinder.getDoGetMapperMethod(c);
    Function<IBoltClient, Long> f3 = c -> NodeIdFinder.getSetterWrapperForCollectionsAndMapsWithNullCheckType(c);

    return Arrays.asList(new Object[][] { { f1, Arrays.asList("Resource", "Binary", "ClassFile") },
        { f2, Arrays.asList("Method") }, { f3, Arrays.asList("Type", "Class") } });
  }
}
