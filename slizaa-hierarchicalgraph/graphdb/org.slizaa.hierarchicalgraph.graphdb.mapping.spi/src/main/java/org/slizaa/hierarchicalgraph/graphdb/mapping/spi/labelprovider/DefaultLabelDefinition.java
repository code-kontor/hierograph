package org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider;

import static com.google.common.base.Preconditions.checkNotNull;

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ILabelDefinitionProvider.ILabelDefinition;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ILabelDefinitionProvider.OverlayPosition;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class DefaultLabelDefinition implements ILabelDefinition {

  /** - */
  private String    _baseImage          = null;

  /** - */
  private String    _overlayTopRight    = null;

  /** - */
  private String    _overlayBottomRight = null;

  /** - */
  private String    _overlayTopLeft     = null;

  /** - */
  private String    _overlayBottomLeft  = null;

  /** - */
  private String _text;
  
  /** - */
  private boolean _isOverlayImage;
  
  @Override
  public boolean isOverlayImage() {
    return _isOverlayImage || _overlayTopRight != null || _overlayBottomRight != null || _overlayTopLeft != null || _overlayBottomLeft != null ;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasBaseImage() {
    return this._baseImage != null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getBaseImagePath() {
    return this._baseImage;
  }

  @Override
  public boolean hasOverlayImage(OverlayPosition overlayPosition) {
    switch (checkNotNull(overlayPosition)) {
    case TOP_RIGHT:
      return this._overlayTopRight != null;
    case TOP_LEFT:
      return this._overlayTopLeft != null;
    case BOTTOM_LEFT:
      return this._overlayBottomLeft != null;
    case BOTTOM_RIGHT:
      return this._overlayBottomRight != null;
    default:
      return false;
    }
  }

  @Override
  public String getOverlayImagePath(OverlayPosition overlayPosition) {
    switch (checkNotNull(overlayPosition)) {
    case TOP_RIGHT:
      return this._overlayTopRight;
    case TOP_LEFT:
      return this._overlayTopLeft;
    case BOTTOM_LEFT:
      return this._overlayBottomLeft;
    case BOTTOM_RIGHT:
      return this._overlayBottomRight;
    default:
      throw new RuntimeException(String.format("Unknown overlay position '%s'.", overlayPosition));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getText() {
    return this._text;
  }

  
  public void setBaseImage(String baseImage) {
    this._baseImage = baseImage;
  }

  public void setOverlayImage(String image, OverlayPosition position) {
    switch (checkNotNull(position)) {
    case TOP_RIGHT:
      this._overlayTopRight = image;
      break;
    case TOP_LEFT:
      this._overlayTopLeft = image;
      break;
    case BOTTOM_LEFT:
      this._overlayBottomLeft = image;
      break;
    case BOTTOM_RIGHT:
      this._overlayBottomRight = image;
      break;
    default:
      throw new RuntimeException(String.format("Unknown overlay position '%s'.", position));
    }
  }

  public void setText(String text) {
    this._text = text;
  }

  public void setIsOverlayImage(boolean isOverlayImage) {
    _isOverlayImage = isOverlayImage;
  }
}
