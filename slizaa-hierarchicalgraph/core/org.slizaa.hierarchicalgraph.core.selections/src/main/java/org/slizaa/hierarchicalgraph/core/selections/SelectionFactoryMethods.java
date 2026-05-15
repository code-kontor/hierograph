package org.slizaa.hierarchicalgraph.core.selections;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;

import org.slizaa.hierarchicalgraph.core.model.AbstractHGDependency;

public class SelectionFactoryMethods {

  /**
   * <p>
   * </p>
   *
   * @param dependencies
   * @return
   */
  public static DependencySelection createDependencySelection(Collection<? extends AbstractHGDependency> dependencies) {
    DependencySelection dependencySelection = SelectionsFactory.eINSTANCE.createDependencySelection();
    dependencySelection.getDependencies().addAll(checkNotNull(dependencies));
    return dependencySelection;
  }
}
