export function setupCanvas(
  canvas: HTMLCanvasElement,
  ctx: CanvasRenderingContext2D,
): void {
  const width = canvas.width;
  const height = canvas.height;
  const deviceRatio = window.devicePixelRatio || 1;

  const bsRatio =
    (ctx as unknown as Record<string, number>)[
      "webkitBackingStorePixelRatio"
    ] ||
    (ctx as unknown as Record<string, number>)["mozBackingStorePixelRatio"] ||
    (ctx as unknown as Record<string, number>)["msBackingStorePixelRatio"] ||
    (ctx as unknown as Record<string, number>)["oBackingStorePixelRatio"] ||
    (ctx as unknown as Record<string, number>)["backingStorePixelRatio"] ||
    1;

  const ratio = deviceRatio / bsRatio;

  if (deviceRatio !== bsRatio) {
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    canvas.style.width = width + "px";
    canvas.style.height = height + "px";
    ctx.scale(ratio, ratio);
  }
}
