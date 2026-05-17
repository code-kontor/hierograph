package org.slizaa.hierarchicalgraph.graphdb.model.impl;

import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;

public class ExtendedGraphDbRootNodeSourceImpl extends GraphDbRootNodeSourceImpl {

  private ExtendedGraphDbNodeSourceTrait _trait;

  /**
   * <p>
   * Creates a new instance of type {@link ExtendedGraphDbRootNodeSourceImpl}.
   * </p>
   */
  public ExtendedGraphDbRootNodeSourceImpl() {
    this._trait = new ExtendedGraphDbNodeSourceTrait(this);
  }


  @Override
  public EMap<String, String> getProperties() {
    return ECollections.emptyEMap();
  }


  @Override
  public EList<String> getLabels() {
    return ECollections.emptyEList();
  }


  public EMap<String, String> reloadProperties() {
    return ECollections.emptyEMap();
  }


  public EList<String> reloadLabels() {
    return ECollections.emptyEList();
  }


  @Override
  public void onExpand() {
    this._trait.onExpand();
  }


  @Override
  public void onCollapse() {
    this._trait.onCollapse();
  }

  @Override
  public boolean isAutoExpand() {
    return this._trait.isAutoExpand();
  }
}
