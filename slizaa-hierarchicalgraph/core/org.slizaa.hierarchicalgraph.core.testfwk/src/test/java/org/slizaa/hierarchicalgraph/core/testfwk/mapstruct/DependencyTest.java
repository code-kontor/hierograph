package org.slizaa.hierarchicalgraph.core.testfwk.mapstruct;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.ClassRule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedGraph;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedTestGraphProviderRule;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class DependencyTest {

  @ClassRule
  public static XmiBasedTestGraphProviderRule _graphProvider                          = new XmiBasedTestGraphProviderRule(
      XmiBasedGraph.MAP_STRUCT);

  /** - */
  public static final Long            ID_PKG_ORG_MAPSTRUCT_AP_INTERNAL_WRITER = new Long(6308);

  /** - */
  public static final Long            ID_TYPE_MODEL_WRITER                    = new Long(5769);

  /**
   * <p>
   * </p>
   */
  @Test
  public void testOutgoingCoreDependencies() {

    // 'mapstruct-1.1.0.Beta2.jar'
    assertThat(_graphProvider.node(1).getOutgoingCoreDependencies()).hasSize(0);
    assertThat(_graphProvider.node(ID_PKG_ORG_MAPSTRUCT_AP_INTERNAL_WRITER).getOutgoingCoreDependencies()).hasSize(0);
    assertThat(_graphProvider.node(ID_TYPE_MODEL_WRITER).getOutgoingCoreDependencies()).hasSize(11);

    // 'mapstruct--processor-1.1.0.Beta2.jar'
    assertThat(_graphProvider.node(577).getAccumulatedOutgoingCoreDependencies()).hasSize(4983);
    assertThat(_graphProvider.node(ID_PKG_ORG_MAPSTRUCT_AP_INTERNAL_WRITER).getAccumulatedOutgoingCoreDependencies())
        .hasSize(75);
    assertThat(_graphProvider.node(ID_TYPE_MODEL_WRITER).getAccumulatedOutgoingCoreDependencies()).hasSize(11);
  }

  @Test
  public void testIncomingCoreDependencies() {

    // 'mapstruct--processor-1.1.0.Beta2.jar'
    assertThat(_graphProvider.node(577).getIncomingCoreDependencies()).hasSize(0);
    assertThat(_graphProvider.node(ID_PKG_ORG_MAPSTRUCT_AP_INTERNAL_WRITER).getIncomingCoreDependencies()).hasSize(0);
    assertThat(_graphProvider.node(ID_TYPE_MODEL_WRITER).getIncomingCoreDependencies()).hasSize(3);

    // 'mapstruct--processor-1.1.0.Beta2.jar'
    assertThat(_graphProvider.node(577).getAccumulatedIncomingCoreDependencies()).hasSize(4983);
    assertThat(_graphProvider.node(ID_PKG_ORG_MAPSTRUCT_AP_INTERNAL_WRITER).getAccumulatedIncomingCoreDependencies())
        .hasSize(58);
    assertThat(_graphProvider.node(ID_TYPE_MODEL_WRITER).getAccumulatedIncomingCoreDependencies()).hasSize(3);
  }

  @Test
  public void testAggregatedDependencies() {

    // '/mapstruct-processor-1.1.0.Beta2.jar' -> 'mapstruct-1.1.0.Beta2.jar'
    assertThat(_graphProvider.node(577).getOutgoingDependenciesTo(_graphProvider.node(1))).isNull();

    // '/mapstruct-processor-1.1.0.Beta2.jar/org.mapstrcut.ap.internal.model' ->
    // '/mapstruct-processor-1.1.0.Beta2.jar/org.mapstrcut.ap.internal.util'
    assertThat(_graphProvider.node(1063).getOutgoingDependenciesTo(_graphProvider.node(5922))).isNotNull();
    assertThat(_graphProvider.node(1063).getOutgoingDependenciesTo(_graphProvider.node(5922)).getAggregatedWeight())
        .isEqualTo(50);
  }
}
