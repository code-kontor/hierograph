/**
 * 
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public interface IDependencyDefinition {

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public long getIdStart();

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public long getIdTarget();

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public long getIdRel();

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public String getType();
}