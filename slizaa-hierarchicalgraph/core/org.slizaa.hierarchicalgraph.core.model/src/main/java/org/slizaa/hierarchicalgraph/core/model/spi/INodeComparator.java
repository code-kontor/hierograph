package org.slizaa.hierarchicalgraph.core.model.spi;

public interface INodeComparator {

  public int category(Object element);

  public int compare(Object e1, Object e2);
}
