package org.slizaa.hierarchicalgraph.core.model.simple;

import static org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactoryFunctions.createNewCoreDependency;
import static org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactoryFunctions.createNewNode;
import static org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactoryFunctions.createNewProxyDependency;
import static org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactoryFunctions.createNewRootNode;

import java.util.function.Supplier;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slizaa.hierarchicalgraph.core.model.CustomFactoryStandaloneSupport;
import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactory;
import org.slizaa.hierarchicalgraph.core.model.IDependencySource;
import org.slizaa.hierarchicalgraph.core.model.INodeSource;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class SimpleTestModelRule implements TestRule {

  /** - */
  private HGNode            _a1;

  /** - */
  private HGNode            _b1;

  /** - */
  private HGNode            _b2;

  /** - */
  private HGNode            _a2;

  /** - */
  private HGNode            _a3;

  /** - */
  private HGNode            _b3;

  /** - */
  private HGCoreDependency  _dep_a1_b1_core1;

  /** - */
  private HGCoreDependency  _dep_a1_b1_core2;

  /** - */
  private HGCoreDependency  _dep_a2_b2_core1;

  /** - */
  private HGProxyDependency _dep_a3_b3_core1;

  /** - */
  private HGRootNode        _rootNode;

  /**
   * {@inheritDoc}
   */
  @Override
  public Statement apply(Statement base, Description description) {

    return new Statement() {

      @Override
      public void evaluate() throws Throwable {

        //
        init();

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
  public HGRootNode root() {
    return this._rootNode;
  }

  /**
   * <p>
   * </p>
   *
   * @return the a1
   */
  public HGNode a1() {
    return this._a1;
  }

  /**
   * <p>
   * </p>
   *
   * @return the b1
   */
  public HGNode b1() {
    return this._b1;
  }

  /**
   * <p>
   * </p>
   *
   * @return the b2
   */
  public HGNode b2() {
    return this._b2;
  }

  /**
   * <p>
   * </p>
   *
   * @return the a2
   */
  public HGNode a2() {
    return this._a2;
  }

  /**
   * <p>
   * </p>
   *
   * @return the a3
   */
  public HGNode a3() {
    return this._a3;
  }

  /**
   * <p>
   * </p>
   *
   * @return the b3
   */
  public HGNode b3() {
    return this._b3;
  }

  /**
   * <p>
   * </p>
   *
   * @return the dep_a1_b1_core1
   */
  public HGCoreDependency a1_b1_core1() {
    return this._dep_a1_b1_core1;
  }

  /**
   * <p>
   * </p>
   *
   * @return the dep_a1_b1_core2
   */
  public HGCoreDependency a1_b1_core2() {
    return this._dep_a1_b1_core2;
  }

  /**
   * <p>
   * </p>
   *
   * @return the dep_a2_b2_core1
   */
  public HGCoreDependency a2_b2_core1() {
    return this._dep_a2_b2_core1;
  }

  /**
   * <p>
   * </p>
   *
   * @return the dep_a3_b3_core1
   */
  public HGProxyDependency a3_b3_core1() {
    return this._dep_a3_b3_core1;
  }

  /**
   * <p>
   * </p>
   */
  private void init() {

    //
    CustomFactoryStandaloneSupport.registerCustomHierarchicalgraphFactory();
    
    //
    Supplier<INodeSource> nodeSourceSupplier = () -> HierarchicalgraphFactory.eINSTANCE.createDefaultNodeSource();
    Supplier<IDependencySource> dependencySourceSupplier = () -> HierarchicalgraphFactory.eINSTANCE
        .createDefaultDependencySource();

    this._rootNode = createNewRootNode(nodeSourceSupplier);

    this._a1 = createNewNode(this._rootNode, this._rootNode, nodeSourceSupplier);
    this._b1 = createNewNode(this._rootNode, this._rootNode, nodeSourceSupplier);

    this._a2 = createNewNode(this._rootNode, this._a1, nodeSourceSupplier);
    this._b2 = createNewNode(this._rootNode, this._b1, nodeSourceSupplier);

    this._a3 = createNewNode(this._rootNode, this._a2, nodeSourceSupplier);
    this._b3 = createNewNode(this._rootNode, this._b2, nodeSourceSupplier);

    this._dep_a1_b1_core1 = createNewCoreDependency(this._a1, this._b1, "USES", dependencySourceSupplier, false);
    this._dep_a1_b1_core2 = createNewCoreDependency(this._a1, this._b1, "DEPENDS_ON", dependencySourceSupplier, false);
    this._dep_a2_b2_core1 = createNewCoreDependency(this._a2, this._b2, "USES", dependencySourceSupplier, false);

    this._dep_a3_b3_core1 = createNewProxyDependency(this._a3, this._b3, "DEPENDS_ON", dependencySourceSupplier, false);
  }

}
