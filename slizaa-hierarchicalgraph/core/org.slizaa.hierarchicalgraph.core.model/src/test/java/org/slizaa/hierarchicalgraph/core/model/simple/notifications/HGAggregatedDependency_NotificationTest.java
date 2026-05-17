package org.slizaa.hierarchicalgraph.core.model.simple.notifications;

import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency;
import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactory;
import org.slizaa.hierarchicalgraph.core.model.simple.SimpleTestModelRule;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphFactoryFunctions.createNewCoreDependency;

public class HGAggregatedDependency_NotificationTest {

  @Rule
  public SimpleTestModelRule        _model = new SimpleTestModelRule();

  private List<Notification>     _notifications;

  private HGAggregatedDependency _aggregatedDependency;

  private Adapter                _adapter;

    @Before
  public void setup() {

    this._notifications = new ArrayList<>();

    this._aggregatedDependency = _model.a1().getOutgoingDependenciesTo(_model.b1());
    assertThat(this._aggregatedDependency).isNotNull();
    assertThat(this._aggregatedDependency.getAggregatedWeight()).isEqualTo(4);
    assertThat(this._aggregatedDependency.getCoreDependencies()).hasSize(4).containsOnly(_model.a1_b1_core1(),
        _model.a1_b1_core2(), _model.a2_b2_core1(), _model.a3_b3_core1());

    this._adapter = new AdapterImpl() {
      @Override
      public void notifyChanged(Notification notification) {
        System.out.println("Notification: " + notification);
        HGAggregatedDependency_NotificationTest.this._notifications.add(notification);
      }
    };

    this._aggregatedDependency.eAdapters().add(this._adapter);
  }

    @After
  public void teardown() {

    this._aggregatedDependency.eAdapters().remove(this._adapter);
  }

    @Test
  public void testHGCoreDependencyNotification() {

    HGCoreDependency newCoreDependdency = createNewCoreDependency(_model.a2(), _model.b2(), "NEW_USAGE",
        () -> HierarchicalgraphFactory.eINSTANCE.createDefaultDependencySource(), true);

    assertThat(this._notifications).hasSize(2);

    assertThat(this._aggregatedDependency.getAggregatedWeight()).isEqualTo(5);
    assertThat(this._aggregatedDependency.getCoreDependencies()).hasSize(5).containsOnly(_model.a1_b1_core1(),
        _model.a1_b1_core2(), _model.a2_b2_core1(), _model.a3_b3_core1(), newCoreDependdency);
  }
}
