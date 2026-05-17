package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import org.slizaa.hierarchicalgraph.core.model.HGNode;

public interface ILabelDefinitionProvider {

    public static enum OverlayPosition {
    TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT;
  }

    public interface ILabelDefinition {

    /**
     * 
     * @return
     */
    boolean isOverlayImage();

        boolean hasBaseImage();

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

        String getText();
  }

    ILabelDefinition getLabelDefinition(HGNode node);
}
