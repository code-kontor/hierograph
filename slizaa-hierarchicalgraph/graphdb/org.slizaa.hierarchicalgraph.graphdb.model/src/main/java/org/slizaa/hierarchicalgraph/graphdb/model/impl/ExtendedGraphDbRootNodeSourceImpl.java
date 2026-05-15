package org.slizaa.hierarchicalgraph.graphdb.model.impl;

import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class ExtendedGraphDbRootNodeSourceImpl extends GraphDbRootNodeSourceImpl {

  /** - */
  private ExtendedGraphDbNodeSourceTrait _trait;

  /**
   * <p>
   * Creates a new instance of type {@link ExtendedGraphDbRootNodeSourceImpl}.
   * </p>
   */
  public ExtendedGraphDbRootNodeSourceImpl() {
    this._trait = new ExtendedGraphDbNodeSourceTrait(this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public EMap<String, String> getProperties() {
    return ECollections.emptyEMap();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public EList<String> getLabels() {
    return ECollections.emptyEList();
  }

  /**
   * {@inheritDoc}
   */
  public EMap<String, String> reloadProperties() {
    return ECollections.emptyEMap();
  }

  /**
   * {@inheritDoc}
   */
  public EList<String> reloadLabels() {
    return ECollections.emptyEList();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onExpand() {
    this._trait.onExpand();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onCollapse() {
    this._trait.onCollapse();
  }

  @Override
  public boolean isAutoExpand() {
    return this._trait.isAutoExpand();
  }
}
