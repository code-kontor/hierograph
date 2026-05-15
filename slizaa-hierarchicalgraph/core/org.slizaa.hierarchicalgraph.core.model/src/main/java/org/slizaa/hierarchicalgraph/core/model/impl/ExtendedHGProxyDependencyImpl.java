package org.slizaa.hierarchicalgraph.core.model.impl;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Optional;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.impl.ENotificationImpl;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphPackage;
import org.slizaa.hierarchicalgraph.core.model.spi.IProxyDependencyResolver;
import org.slizaa.hierarchicalgraph.core.model.spi.IProxyDependencyResolver.IProxyDependencyResolverJob;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class ExtendedHGProxyDependencyImpl extends HGProxyDependencyImpl {

  /**
   * {@inheritDoc}
   */
  @Override
  public HGRootNode getRootNode() {
    return getFrom().getRootNode();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Optional<T> getDependencySource(Class<T> clazz) {

    //
    if (checkNotNull(clazz).isInstance(dependencySource)) {
      return Optional.of(clazz.cast(dependencySource));
    }

    return Optional.empty();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void resolveProxyDependencies() {

    //
    if (!resolved) {
      Utilities.resolveProxyDependencies(null, this);
    }
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public IProxyDependencyResolverJob onResolveProxyDependency() {

    //
    if (!resolved) {

      //
      if (getRootNode().hasExtension(IProxyDependencyResolver.class)) {
        return getRootNode().getExtension(IProxyDependencyResolver.class).resolveProxyDependency(this);
      }
    }

    //
    return null;
  }

  /**
   * <p>
   * </p>
   *
   * @param newResolved
   */
  void setResolved(boolean newResolved) {
    boolean oldResolved = resolved;
    resolved = newResolved;
    if (eNotificationRequired())
      eNotify(new ENotificationImpl(this, Notification.SET,
          HierarchicalgraphPackage.HG_PROXY_DEPENDENCY__RESOLVED, oldResolved, resolved));
  }
}
