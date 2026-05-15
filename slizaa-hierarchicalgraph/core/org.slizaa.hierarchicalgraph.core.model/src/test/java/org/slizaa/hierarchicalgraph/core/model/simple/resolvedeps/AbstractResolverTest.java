package org.slizaa.hierarchicalgraph.core.model.simple.resolvedeps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactoryFunctions.createNewCoreDependency;

import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactory;
import org.slizaa.hierarchicalgraph.core.model.simple.SimpleTestModelRule;
import org.slizaa.hierarchicalgraph.core.model.spi.IProxyDependencyResolver;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public abstract class AbstractResolverTest implements IProxyDependencyResolver {

  /** - */
  @Rule
  public SimpleTestModelRule   _model = new SimpleTestModelRule();

  /** - */
  private HGCoreDependency _newDependency_1;

  /** - */
  private HGCoreDependency _newDependency_2;

  @Before
  public void before() {

    //
    this._model.root().registerExtension(IProxyDependencyResolver.class, this);
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public SimpleTestModelRule model() {
    return this._model;
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public HGCoreDependency getNewDependency_1() {
    return this._newDependency_1;
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public HGCoreDependency getNewDependency_2() {
    return this._newDependency_2;
  }

  
  
  /**
   * {@inheritDoc}
   */
  @Override
  public IProxyDependencyResolverJob resolveProxyDependency(HGProxyDependency dependencyToResolve) {

    this._newDependency_1 = createNewCoreDependency(dependencyToResolve.getFrom(), dependencyToResolve.getTo(),
        "NEW_USAGE", () -> HierarchicalgraphFactory.eINSTANCE.createDefaultDependencySource(), false);

    this._newDependency_2 = createNewCoreDependency(dependencyToResolve.getFrom(), dependencyToResolve.getTo(),
        "NEW_USAGE", () -> HierarchicalgraphFactory.eINSTANCE.createDefaultDependencySource(), false);

    dependencyToResolve.getResolvedCoreDependencies().add(this._newDependency_1);
    dependencyToResolve.getResolvedCoreDependencies().add(this._newDependency_2);

    // return null as we resolved the dependencies immediately
    return null;
  }

  /**
   * <p>
   * </p>
   *
   * @param runnable
   */
  protected void resolve(Runnable runnable) {

    //
    assertDependenciesBeforeResolve();

    //
    runnable.run();

    //
    assertDependenciesAfterResolve();
  }

  /**
   * <p>
   * </p>
   */
  private void assertDependenciesBeforeResolve() {

    //
    HGAggregatedDependency aggregatedDependency = this._model.a1().getOutgoingDependenciesTo(this._model.b1());
    assertThat(aggregatedDependency).isNotNull();
    assertThat(aggregatedDependency.getAggregatedWeight()).isEqualTo(4);
    assertThat(aggregatedDependency.getCoreDependencies()).isNotNull();
    assertThat(aggregatedDependency.getCoreDependencies()).hasSize(4).contains(this._model.a1_b1_core1(),
        this._model.a1_b1_core2(), this._model.a2_b2_core1(), this._model.a3_b3_core1());

    //
    List<HGCoreDependency> outgoingDependencies = this._model.a1().getAccumulatedOutgoingCoreDependencies();
    assertThat(outgoingDependencies).hasSize(4).contains(this._model.a1_b1_core1(), this._model.a1_b1_core2(),
        this._model.a2_b2_core1(), this._model.a3_b3_core1());

    //
    List<HGCoreDependency> incomingDependencies = this._model.b1().getAccumulatedIncomingCoreDependencies();
    assertThat(incomingDependencies).hasSize(4).contains(this._model.a1_b1_core1(), this._model.a1_b1_core2(),
        this._model.a2_b2_core1(), this._model.a3_b3_core1());
  }

  /**
   * <p>
   * </p>
   */
  private void assertDependenciesAfterResolve() {

    // we have to re-read the aggregated dependency
    List<HGCoreDependency> incomingDeps = this._model.b1().getAccumulatedIncomingCoreDependencies();
    assertThat(incomingDeps).isNotNull();
    assertThat(incomingDeps).hasSize(5);
    assertThat(incomingDeps).contains(this._model.a1_b1_core1(), this._model.a1_b1_core2(), this._model.a2_b2_core1(),
        getNewDependency_1(), getNewDependency_2());

    List<HGCoreDependency> outgoingDeps = this._model.a1().getAccumulatedOutgoingCoreDependencies();
    assertThat(outgoingDeps).isNotNull();
    assertThat(outgoingDeps).hasSize(5);
    assertThat(outgoingDeps).contains(this._model.a1_b1_core1(), this._model.a1_b1_core2(), this._model.a2_b2_core1(),
        getNewDependency_1(), getNewDependency_2());

    //
    HGAggregatedDependency aggregatedDependency = this._model.a1().getOutgoingDependenciesTo(this._model.b1());
    assertThat(aggregatedDependency.getAggregatedWeight()).isEqualTo(4);
    assertThat(aggregatedDependency.getCoreDependencies()).isNotNull();
    assertThat(aggregatedDependency.getCoreDependencies()).hasSize(5).contains(this._model.a1_b1_core1(),
        this._model.a1_b1_core2(), this._model.a2_b2_core1(), getNewDependency_1(), getNewDependency_2());
  }
}