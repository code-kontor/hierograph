package org.slizaa.hierarchicalgraph.core.model.impl;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Optional;

import org.slizaa.hierarchicalgraph.core.model.HGRootNode;

public class ExtendedHGCoreDependencyImpl extends HGCoreDependencyImpl {


  @Override
  public HGRootNode getRootNode() {
    return getFrom().getRootNode();
  }
  
  @Override
  public <T> Optional<T> getDependencySource(Class<T> clazz) {

    if (checkNotNull(clazz).isInstance(dependencySource)) {
      return Optional.of(clazz.cast(dependencySource));
    }
    
    return Optional.empty();
  }
}
