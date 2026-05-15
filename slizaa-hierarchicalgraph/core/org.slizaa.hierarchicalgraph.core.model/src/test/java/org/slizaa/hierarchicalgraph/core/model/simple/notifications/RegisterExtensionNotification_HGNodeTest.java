package org.slizaa.hierarchicalgraph.core.model.simple.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slizaa.hierarchicalgraph.core.model.simple.SimpleTestModelRule;
import org.slizaa.hierarchicalgraph.core.model.spi.INodeComparator;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class RegisterExtensionNotification_HGNodeTest {

  /** - */
  @Rule
  public SimpleTestModelRule _model = new SimpleTestModelRule();

  /** - */
  private List<Notification> _notifications;

  /** - */
  private Adapter            _adapter;

  /**
   * <p>
   * </p>
   */
  @Before
  public void setup() {

    //
    this._notifications = new ArrayList<>();

    //
    this._adapter = new AdapterImpl() {
      @Override
      public void notifyChanged(Notification notification) {
        System.out.println("Notification: " + notification);
        RegisterExtensionNotification_HGNodeTest.this._notifications.add(notification);
      }
    };

    //
    this._model.root().eAdapters().add(this._adapter);
  }

  /**
   * <p>
   * </p>
   */
  @After
  public void teardown() {

    //
    this._model.root().eAdapters().remove(this._adapter);
  }

  /**
   * <p>
   * </p>
   */
  @Test
  public void testHGNodeOutgoingDependenciesNotification() {

    //
    assertThat(this._notifications).isEmpty();

    //
    this._model.root().registerExtension(INodeComparator.class, new DummyNodeComparator());
    assertThat(this._notifications).hasSize(1);

    this._model.root().registerExtension(INodeComparator.class, new DummyNodeComparator());
    assertThat(this._notifications).hasSize(2);

    //
  }

  /**
   * <p>
   * </p>
   *
   * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
   */
  private static class DummyNodeComparator implements INodeComparator {

    @Override
    public int compare(Object e1, Object e2) {
      return 0;
    }

    @Override
    public int category(Object element) {
      return 0;
    }
  }
}
