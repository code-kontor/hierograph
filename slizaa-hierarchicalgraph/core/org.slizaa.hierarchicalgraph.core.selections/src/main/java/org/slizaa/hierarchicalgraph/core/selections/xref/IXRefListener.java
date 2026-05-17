package org.slizaa.hierarchicalgraph.core.selections.xref;

public interface IXRefListener {

  void coreDependenciesChanged();

  void centerNodeSelectionChanged();

  void leftsidedNodeSelectionChanged();

  void rightsidedNodeSelectionChanged();
  
  void croppedSelectionChanged();

    public static class Adapter implements IXRefListener {

    @Override
    public void coreDependenciesChanged() {
    }

    @Override
    public void centerNodeSelectionChanged() {
    }

    @Override
    public void leftsidedNodeSelectionChanged() {
    }

    @Override
    public void rightsidedNodeSelectionChanged() {
    }

    @Override
    public void croppedSelectionChanged() {
    }
  }
}
