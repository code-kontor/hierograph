import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { useEffect, useRef } from "react";

import { resolveGraphColors } from "./colorScheme";
import { DependencyDiagramControls } from "./DependencyDiagramControls";
import { setupCanvas } from "./dpiFixer";
import { drawGraph } from "./drawGraph";
import { FIT_PADDING, fitToView, pan, type Viewport, zoomAt } from "./viewport";

const ZOOM_SENSITIVITY = 0.0015;
const BUTTON_ZOOM_FACTOR = 1.2;
const IDENTITY_VIEWPORT: Viewport = { scale: 1, translateX: 0, translateY: 0 };

type DependencyDiagramCanvasProps = { rootNode: ElkNode };

export function DependencyDiagramCanvas({
  rootNode,
}: DependencyDiagramCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const rootNodeRef = useRef(rootNode);
  const sizeRef = useRef({ width: 0, height: 0 });
  const viewportRef = useRef<Viewport>(IDENTITY_VIEWPORT);
  const needsFitRef = useRef(true);
  const rafRef = useRef<number | null>(null);

  const isDraggingRef = useRef(false);
  const dragStartClientRef = useRef({ x: 0, y: 0 });
  const dragStartViewportRef = useRef<Viewport>(IDENTITY_VIEWPORT);

  function redraw() {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;

    const { width, height } = sizeRef.current;
    if (width <= 0 || height <= 0) return;

    // Setting canvas.width/height resets the context state, so the DPI and
    // viewport transforms must be re-established on every redraw.
    canvas.width = width;
    canvas.height = height;
    setupCanvas(canvas, ctx);
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    const vp = viewportRef.current;
    ctx.save();
    ctx.translate(vp.translateX, vp.translateY);
    ctx.scale(vp.scale, vp.scale);
    drawGraph(ctx, rootNodeRef.current, resolveGraphColors());
    ctx.restore();
  }

  function scheduleRedraw() {
    if (rafRef.current !== null) return;
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null;
      redraw();
    });
  }

  function fitViewport() {
    const { width, height } = sizeRef.current;
    const node = rootNodeRef.current;
    viewportRef.current = fitToView(
      node.width,
      node.height,
      width,
      height,
      FIT_PADDING,
    );
  }

  // (b) Measure the container that fills the pane; a resize redraws with the
  // current viewport but never re-fits (that would reset the user's view).
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    function applySize(width: number, height: number) {
      sizeRef.current = { width, height };
      if (needsFitRef.current && width > 0 && height > 0) {
        needsFitRef.current = false;
        fitViewport();
      }
      scheduleRedraw();
    }

    applySize(container.clientWidth, container.clientHeight);

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) return;
      applySize(entry.contentRect.width, entry.contentRect.height);
    });
    observer.observe(container);
    return () => observer.disconnect();
    // scheduleRedraw/fitViewport close only over refs, so they don't need to
    // re-run this effect; runs once on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // (e) A new rootNode (selection/layout change) re-fits; a pending fit is
  // consumed by the next valid size if the container isn't measured yet.
  useEffect(() => {
    rootNodeRef.current = rootNode;
    needsFitRef.current = true;
    const { width, height } = sizeRef.current;
    if (width > 0 && height > 0) {
      needsFitRef.current = false;
      fitViewport();
    }
    scheduleRedraw();
    // scheduleRedraw/fitViewport close only over refs, no need to re-run on their identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rootNode]);

  // (f) React's onWheel is passive, so preventDefault() would not reliably
  // stop the page from scrolling/zooming; a native listener is required.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    function handleWheel(e: WheelEvent) {
      e.preventDefault();
      const rect = canvas!.getBoundingClientRect();
      const sx = e.clientX - rect.left;
      const sy = e.clientY - rect.top;
      const factor = Math.exp(-e.deltaY * ZOOM_SENSITIVITY);
      viewportRef.current = zoomAt(viewportRef.current, sx, sy, factor);
      scheduleRedraw();
    }

    canvas.addEventListener("wheel", handleWheel, { passive: false });
    return () => canvas.removeEventListener("wheel", handleWheel);
    // scheduleRedraw closes only over refs, no need to re-run on its identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    return () => {
      if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
    };
  }, []);

  function handleFit() {
    fitViewport();
    scheduleRedraw();
  }

  function handleZoomIn() {
    const { width, height } = sizeRef.current;
    viewportRef.current = zoomAt(
      viewportRef.current,
      width / 2,
      height / 2,
      BUTTON_ZOOM_FACTOR,
    );
    scheduleRedraw();
  }

  function handleZoomOut() {
    const { width, height } = sizeRef.current;
    viewportRef.current = zoomAt(
      viewportRef.current,
      width / 2,
      height / 2,
      1 / BUTTON_ZOOM_FACTOR,
    );
    scheduleRedraw();
  }

  return (
    <div ref={containerRef} className="relative h-full w-full">
      <canvas
        ref={canvasRef}
        data-testid="dependency-diagram-canvas"
        className="absolute inset-0 block h-full w-full"
        onPointerDown={(e) => {
          // #0127/#0129: hit-test node here; if hit, start node-drag instead of pan
          e.currentTarget.setPointerCapture(e.pointerId);
          isDraggingRef.current = true;
          dragStartClientRef.current = { x: e.clientX, y: e.clientY };
          dragStartViewportRef.current = viewportRef.current;
        }}
        onPointerMove={(e) => {
          if (!isDraggingRef.current) return;
          const dx = e.clientX - dragStartClientRef.current.x;
          const dy = e.clientY - dragStartClientRef.current.y;
          viewportRef.current = pan(dragStartViewportRef.current, dx, dy);
          scheduleRedraw();
        }}
        onPointerUp={(e) => {
          isDraggingRef.current = false;
          e.currentTarget.releasePointerCapture(e.pointerId);
        }}
        onPointerCancel={(e) => {
          isDraggingRef.current = false;
          e.currentTarget.releasePointerCapture(e.pointerId);
        }}
      />
      <div className="absolute top-2 right-2 z-10 flex gap-1">
        <DependencyDiagramControls
          onFit={handleFit}
          onZoomIn={handleZoomIn}
          onZoomOut={handleZoomOut}
        />
      </div>
    </div>
  );
}
