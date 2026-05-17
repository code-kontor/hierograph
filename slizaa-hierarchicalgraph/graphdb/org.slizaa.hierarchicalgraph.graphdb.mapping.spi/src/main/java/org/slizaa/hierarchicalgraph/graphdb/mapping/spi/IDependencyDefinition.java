/**
 * 
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

public interface IDependencyDefinition {

    public long getIdStart();

    public long getIdTarget();

    public long getIdRel();

    public String getType();

  default int getWeight() {
    return 1;
  }
}