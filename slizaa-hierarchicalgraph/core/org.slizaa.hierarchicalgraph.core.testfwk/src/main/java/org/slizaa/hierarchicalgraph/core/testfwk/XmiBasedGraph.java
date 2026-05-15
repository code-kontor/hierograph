package org.slizaa.hierarchicalgraph.core.testfwk;

import static com.google.common.base.Preconditions.checkNotNull;

public enum XmiBasedGraph {

  //
  MAP_STRUCT("mapstruct_1-1-0-Beta2.hggraph"), EUREKA("eureka_1-4-10.hggraph"), EUREKA_AGGREGATED(
      "eureka_1-4-10-aggregated.hggraph");

  private XmiBasedGraph(String xmiFileName) {
    _xmiFileName = checkNotNull(xmiFileName);
  }
  
  public String getXmiFileName() {
    return _xmiFileName;
  }

  private String _xmiFileName;
}
