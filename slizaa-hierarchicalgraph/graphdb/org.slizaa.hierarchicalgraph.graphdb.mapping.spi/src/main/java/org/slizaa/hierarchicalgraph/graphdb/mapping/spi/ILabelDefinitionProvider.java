package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import org.slizaa.hierarchicalgraph.core.model.HGNode;

public interface ILabelDefinitionProvider {

  /**
   * <p>
   * </p>
   *
   * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
   */
  public static enum OverlayPosition {
    TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT;
  }

  /**
   * <p>
   * </p>
   *
   * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
   */
  public interface ILabelDefinition {

    /**
     * 
     * @return
     */
    boolean isOverlayImage();

    /**
     * <p>
     * </p>
     *
     * @return
     */
    boolean hasBaseImage();

    /**
     * <p>
     * </p>
     *
     * @return
     */
    String getBaseImagePath();

    /**
     * <p>
     * </p>
     *
     * @param overlayPosition
     * @return
     */
    boolean hasOverlayImage(OverlayPosition overlayPosition);

    /**
     * <p>
     * </p>
     *
     * @param overlayPosition
     * @return
     */
    String getOverlayImagePath(OverlayPosition overlayPosition);

    /**
     * <p>
     * </p>
     *
     * @return
     */
    String getText();
  }

  /**
   * <p>
   * </p>
   */
  ILabelDefinition getLabelDefinition(HGNode node);
}
