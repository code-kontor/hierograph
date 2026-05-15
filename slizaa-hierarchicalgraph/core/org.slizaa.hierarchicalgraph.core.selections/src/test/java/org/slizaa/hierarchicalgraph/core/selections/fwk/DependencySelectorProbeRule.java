package org.slizaa.hierarchicalgraph.core.selections.fwk;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.selections.selector.DefaultDependencySelector;
import org.slizaa.hierarchicalgraph.core.selections.selector.IDependencySelector;
import org.slizaa.hierarchicalgraph.core.testfwk.XmiBasedTestGraphProviderRule;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class DependencySelectorProbeRule implements TestRule {

  /** - */
  private XmiBasedTestGraphProviderRule  _testGraphProvider;

  /** - */
  private HGAggregatedDependency _aggregatedDependency;

  /** - */
  private IDependencySelector    _dependencySelector;

  /**
   * <p>
   * Creates a new instance of type {@link DependencySelectorProbeRule}.
   * </p>
   *
   * @param testGraphProvider
   */
  public DependencySelectorProbeRule(XmiBasedTestGraphProviderRule testGraphProvider) {
    _testGraphProvider = checkNotNull(testGraphProvider);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {

      @Override
      public void evaluate() throws Throwable {

        // ap.internal.model -> ap.internal.util
        _aggregatedDependency = _testGraphProvider.node(1063).getOutgoingDependenciesTo(_testGraphProvider.node(5922));
        assertThat(_aggregatedDependency).isNotNull();
        assertThat(_testGraphProvider.node(1063).getOutgoingDependenciesTo(_testGraphProvider.node(5922))
            .getAggregatedWeight()).isEqualTo(50);

        //
        _dependencySelector = new DefaultDependencySelector();
        _dependencySelector.setUnfilteredCoreDependencies(_aggregatedDependency.getCoreDependencies());

        //
        base.evaluate();
      }
    };
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public IDependencySelector dependencySelector() {
    return _dependencySelector;
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public HGAggregatedDependency aggregatedDependency() {
    return _aggregatedDependency;
  }
}
