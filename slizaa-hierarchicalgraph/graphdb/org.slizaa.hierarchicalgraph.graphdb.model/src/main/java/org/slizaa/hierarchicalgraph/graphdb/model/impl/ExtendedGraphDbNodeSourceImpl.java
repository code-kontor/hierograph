package org.slizaa.hierarchicalgraph.graphdb.model.impl;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;

public class ExtendedGraphDbNodeSourceImpl extends GraphDbNodeSourceImpl {

  private ExtendedGraphDbNodeSourceTrait _trait;

  /**
   * <p>
   * Creates a new instance of type {@link ExtendedGraphDbNodeSourceImpl}.
   * </p>
   */
  public ExtendedGraphDbNodeSourceImpl() {
    this._trait = new ExtendedGraphDbNodeSourceTrait(this);
  }

  public ExtendedGraphDbNodeSourceTrait getTrait() {
    return this._trait;
  }


  @Override
  public EMap<String, String> getProperties() {
    return this._trait.getProperties();
  }


  @Override
  public EList<String> getLabels() {
    return this._trait.getLabels();
  }

  // public EMap<String, String> reloadProperties() {
  // return _trait.reloadProperties();
  // }
  // public EList<String> reloadLabels() {
  // return _trait.reloadLabels();
  // }

  @Override
  public void onExpand() {
    this._trait.onExpand();
  }

  @Override
  public void onCollapse() {
    this._trait.onCollapse();
  }

  @Override
  public void onSelect() {
    this._trait.onSelect();
  }

  @Override
  public boolean isAutoExpand() {
    return this._trait.isAutoExpand();
  }
}
