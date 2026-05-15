package org.slizaa.hierarchicalgraph.core.selections.selector;

import org.slizaa.hierarchicalgraph.core.model.SourceOrTarget;

public class SelectedNodesChangedEvent {

  private SourceOrTarget _selectedNodesType;

  public SelectedNodesChangedEvent(SourceOrTarget selectedNodesType) {
    _selectedNodesType = selectedNodesType;
  }

  public boolean hasSelectedNodes() {
    return _selectedNodesType != null;
  }

  public SourceOrTarget getSelectedNodesType() {
    return _selectedNodesType;
  }
}
