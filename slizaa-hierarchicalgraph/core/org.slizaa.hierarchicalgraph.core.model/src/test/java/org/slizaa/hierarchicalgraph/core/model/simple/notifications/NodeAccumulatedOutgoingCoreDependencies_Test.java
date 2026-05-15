package org.slizaa.hierarchicalgraph.core.model.simple.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactoryFunctions.createNewCoreDependency;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactory;
import org.slizaa.hierarchicalgraph.core.model.simple.SimpleTestModelRule;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class NodeAccumulatedOutgoingCoreDependencies_Test {

  /** - */
  @Rule
  public SimpleTestModelRule    _model = new SimpleTestModelRule();

  /** - */
  private List<Notification> _notifications;

  /** - */
  private Adapter            _adapter;

  /** - */
  private HGNode             _node;

  /**
   * <p>
   * </p>
   */
  @Before
  public void setup() {

    //
    this._notifications = new ArrayList<>();

    //
    this._node = _model.a1();
    assertThat(this._node).isNotNull();
    assertThat(this._node.getAccumulatedOutgoingCoreDependencies()).isNotNull();
    assertThat(this._node.getAccumulatedOutgoingCoreDependencies()).hasSize(4);
    assertThat(this._node.getAccumulatedOutgoingCoreDependencies()).containsOnly(_model.a1_b1_core1(),
        _model.a1_b1_core2(), _model.a2_b2_core1(), _model.a3_b3_core1());

    //
    this._adapter = new AdapterImpl() {
      @Override
      public void notifyChanged(Notification notification) {
        System.out.println("Notification: " + notification);
        NodeAccumulatedOutgoingCoreDependencies_Test.this._notifications.add(notification);
      }
    };

    //
    this._node.eAdapters().add(this._adapter);
  }

  /**
   * <p>
   * </p>
   */
  @After
  public void teardown() {

    //
    this._node.eAdapters().remove(this._adapter);
  }

  /**
   * <p>
   * </p>
   */
  @Test
  public void testHGNodeOutgoingDependenciesNotification() {

    //
    HGCoreDependency newCoreDependency = createNewCoreDependency(_model.a2(), _model.b2(), "NEW_USAGE",
        () -> HierarchicalgraphFactory.eINSTANCE.createDefaultDependencySource(), true);

    //
    assertThat(this._notifications).hasSize(1);

    //
    assertThat(this._node.getAccumulatedOutgoingCoreDependencies()).hasSize(5);
    assertThat(this._node.getAccumulatedOutgoingCoreDependencies()).containsOnly(_model.a1_b1_core1(),
        _model.a1_b1_core2(), _model.a2_b2_core1(), _model.a3_b3_core1(), newCoreDependency);
  }

}
