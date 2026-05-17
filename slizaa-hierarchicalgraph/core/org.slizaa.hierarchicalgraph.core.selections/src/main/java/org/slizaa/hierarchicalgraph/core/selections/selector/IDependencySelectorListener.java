package org.slizaa.hierarchicalgraph.core.selections.selector;

public interface IDependencySelectorListener {

    void selectedNodesChanged(SelectedNodesChangedEvent event);

    void unfilteredDependenciesChanged(UnfilteredDependenciesChangedEvent event);

    void proxyDependencyChanged(ProxyDependencyChangedEvent event);

    public static class Adapter implements IDependencySelectorListener {

    @Override
    public void selectedNodesChanged(SelectedNodesChangedEvent event) {
    }

    @Override
    public void unfilteredDependenciesChanged(UnfilteredDependenciesChangedEvent event) {
    }

    @Override
    public void proxyDependencyChanged(ProxyDependencyChangedEvent event) {
    }
  }
}
