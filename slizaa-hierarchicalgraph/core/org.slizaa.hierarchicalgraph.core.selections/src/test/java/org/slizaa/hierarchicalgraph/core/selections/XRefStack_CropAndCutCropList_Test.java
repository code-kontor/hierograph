package org.slizaa.hierarchicalgraph.core.selections;

import java.util.Collections;

import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.selections.fwk.XRefStackProbeRule;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedGraph;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedTestGraphProviderRule;

public class XRefStack_CropAndCutCropList_Test {

  @ClassRule
  public static XmiBasedTestGraphProviderRule _graphProvider = new XmiBasedTestGraphProviderRule(XmiBasedGraph.EUREKA_AGGREGATED);

  @ClassRule
  public static XRefStackProbeRule    _probe         = new XRefStackProbeRule();

  @Test
  @Ignore
  // TODO
  public void test() {

    /** STEP 1: select '/WEB-INF/lib/aws-java-sdk-autoscaling-1.11.9.jar (124105)' */
    _probe.xRefStack().pruneDependenciesForUncroppedCenterNodes(Collections.singletonList(_graphProvider.node(124105)),
        _graphProvider.node(124105).getAccumulatedIncomingCoreDependencies(),
        _graphProvider.node(124105).getAccumulatedOutgoingCoreDependencies());
    assertSelection_1();

    /** STEP 2: crop selection */
    _probe.xRefStack().cropSelection();
    // TODO: CROP MODE!!
    assertSelection_1();

    /** Step 3: select right-sided node '/WEB-INF/lib/commons-logging-1.2.jar (346380)' */
    _probe.xRefStack().setSelectedRightsidedNodes(_graphProvider.nodes(346380));
    _probe.xRefStack().cropSelection();
    assertSelection_2_a();

    /** Step 4: go back */
    _probe.xRefStack().goBack();
    assertSelection_1();

    /** Step 5: select right-sided node '/WEB-INF/lib/aws-java-sdk-core-1.11.9.jar (83045)' */
    _probe.xRefStack().setSelectedRightsidedNodes(_graphProvider.nodes(83045));
    _probe.xRefStack().cropSelection();
    assertSelection_2b();

    /** Step 6: go back */
    _probe.xRefStack().goBack();
    assertSelection_1();

    /** Step 7: go back */
    // TODO: CHECK THIS!!
    // _probe.xRefStack().goBack();
    // assertSelection_1();
  }

  /**
   * <p>
   * </p>
   */
  private void assertSelection_1() {

    // assert center:
    // - '/WEB-INF/lib/aws-java-sdk-autoscaling-1.11.9.jar (124105)'
    _probe.assertCenterNodes(314, 124105);

    // assert left:
    // - '/WEB-INF/lib/eureka-core-1.6.1.jar (6303)'
    // - '/WEB-INF/lib/aws-java-sdk-autoscaling-1.11.9.jar (124105)'
    _probe.assertLeftsidedNodes(213, 6303, 124105);

    // assert right:
    // - '/WEB-INF/lib/aws-java-sdk-core-1.11.9.jar (83045)'
    // - '/WEB-INF/lib/aws-java-sdk-autoscaling-1.11.9.jar (124105)'
    // - '/WEB-INF/lib/stax-api-1.0-2.jar (251957)'
    // - '/WEB-INF/lib/stax-api-1.0.1.jar (281112)'
    // - '/WEB-INF/lib/commons-logging-1.2.jar (346380)'
    _probe.assertRightsidedNodes(355, 83045, 124105, 251957, 281112, 346380);
  }

  /**
   * <p>
   * </p>
   */
  private void assertSelection_2_a() {

    // assert center:
    // - '/WEB-INF/lib/aws-java-sdk-autoscaling-1.11.9.jar (124105)'
    _probe.assertCenterNodes(4, 124105);

    // assert left:
    // - '/WEB-INF/lib/eureka-core-1.6.1.jar (6303)'
    // - '/WEB-INF/lib/aws-java-sdk-autoscaling-1.11.9.jar (124105)'
    _probe.assertLeftsidedNodes(7, 6303, 124105);

    // assert right:
    // - '/WEB-INF/lib/commons-logging-1.2.jar (346380)'
    _probe.assertRightsidedNodes(5, 346380);
  }

  /**
   * <p>
   * </p>
   */
  private void assertSelection_2b() {

    // assert center:
    // - '/WEB-INF/lib/aws-java-sdk-autoscaling-1.11.9.jar (124105)'
    _probe.assertCenterNodes(268, 124105);

    // assert left:
    // - '/WEB-INF/lib/eureka-core-1.6.1.jar (6303)'
    // - '/WEB-INF/lib/aws-java-sdk-autoscaling-1.11.9.jar (124105)'
    _probe.assertLeftsidedNodes(147, 6303, 124105);

    // assert right:
    // - '/WEB-INF/lib/aws-java-sdk-core-1.11.9.jar (83045)'
    _probe.assertRightsidedNodes(54, 83045);
  }
}
