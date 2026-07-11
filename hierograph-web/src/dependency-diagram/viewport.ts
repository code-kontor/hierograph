export type Viewport = {
  scale: number;
  translateX: number;
  translateY: number;
};

export const MIN_SCALE = 0.1;
export const MAX_SCALE = 4;
export const FIT_PADDING = 24;

export function clampScale(scale: number): number {
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
}

export function worldToScreen(
  vp: Viewport,
  x: number,
  y: number,
): { x: number; y: number } {
  return { x: x * vp.scale + vp.translateX, y: y * vp.scale + vp.translateY };
}

export function screenToWorld(
  vp: Viewport,
  x: number,
  y: number,
): { x: number; y: number } {
  return {
    x: (x - vp.translateX) / vp.scale,
    y: (y - vp.translateY) / vp.scale,
  };
}

export function pan(
  vp: Viewport,
  dxScreen: number,
  dyScreen: number,
): Viewport {
  return {
    scale: vp.scale,
    translateX: vp.translateX + dxScreen,
    translateY: vp.translateY + dyScreen,
  };
}

export function zoomAt(
  vp: Viewport,
  screenX: number,
  screenY: number,
  factor: number,
): Viewport {
  const newScale = clampScale(vp.scale * factor);
  const world = screenToWorld(vp, screenX, screenY);
  return {
    scale: newScale,
    translateX: screenX - world.x * newScale,
    translateY: screenY - world.y * newScale,
  };
}

export function fitToView(
  contentWidth: number | undefined,
  contentHeight: number | undefined,
  viewWidth: number,
  viewHeight: number,
  padding: number,
): Viewport {
  if (!contentWidth || !contentHeight || viewWidth <= 0 || viewHeight <= 0) {
    return { scale: 1, translateX: 0, translateY: 0 };
  }

  const scale = clampScale(
    Math.min(
      (viewWidth - 2 * padding) / contentWidth,
      (viewHeight - 2 * padding) / contentHeight,
    ),
  );

  return {
    scale,
    translateX: (viewWidth - contentWidth * scale) / 2,
    translateY: (viewHeight - contentHeight * scale) / 2,
  };
}
