package org.slizaa.hierarchicalgraph.core.model.spi;

import org.slizaa.hierarchicalgraph.core.model.HGNode;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public interface IAutoExpandInterceptor {

  /**
   * <p>
   * </p>
   *
   * @return
   */
  boolean preventAutoExpansion(HGNode node);
}
