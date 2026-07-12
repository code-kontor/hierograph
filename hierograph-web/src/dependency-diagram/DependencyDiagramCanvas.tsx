import type { ElkExtendedEdge, ElkNode } from "elkjs/lib/elk.bundled.js";
import { useEffect, useRef, useState } from "react";

import { NodeInfoTooltip } from "@/graph/NodeInfoTooltip";
import { type NodeLabelFormat, shortNameOf } from "@/graph/nodeLabel";

import { resolveGraphColors } from "./colorScheme";
import { DependencyDiagramControls } from "./DependencyDiagramControls";
import { setupCanvas } from "./dpiFixer";
import { drawGraph } from "./drawGraph";
import { hitTestEdge } from "./edgeHitTest";
import type { DiagramElkNode } from "./elkLayout";
import { hitTestNode } from "./hitTest";
import {
  FIT_PADDING,
  fitToView,
  pan,
  screenToWorld,
  type Viewport,
  zoomAt,
} from "./viewport";

const ZOOM_SENSITIVITY = 0.0015;
const BUTTON_ZOOM_FACTOR = 1.2;
const CLICK_DRAG_THRESHOLD = 4;
const IDENTITY_VIEWPORT: Viewport = { scale: 1, translateX: 0, translateY: 0 };
const TOOLTIP_HOVER_DELAY_MS = 300;
const TOOLTIP_OFFSET_X = 18;
const TOOLTIP_OFFSET_Y = 20;

type DependencyDiagramCanvasProps = {
  rootNode: ElkNode;
  labelFormat: NodeLabelFormat;
  onNodeActivate?: (id: string, label: string) => void;
  onNodeToggleExpand?: (id: string) => void;
  onEdgeActivate?: (sourceNodeId: string, targetNodeId: string) => void;
};

type NodeTooltip = {
  shortName: string;
  type: string;
  fullName: string;
  x: number;
  y: number;
};

export function DependencyDiagramCanvas({
  rootNode,
  labelFormat,
  onNodeActivate,
  onNodeToggleExpand,
  onEdgeActivate,
}: DependencyDiagramCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const rootNodeRef = useRef(rootNode);
  const labelFormatRef = useRef(labelFormat);
  const sizeRef = useRef({ width: 0, height: 0 });
  const viewportRef = useRef<Viewport>(IDENTITY_VIEWPORT);
  const needsFitRef = useRef(true);
  const rafRef = useRef<number | null>(null);
  const hoveredIdRef = useRef<string | null>(null);
  const hoveredEdgeIdRef = useRef<string | null>(null);
  const tooltipTimerRef = useRef<number | null>(null);

  const isDraggingRef = useRef(false);
  const dragStartClientRef = useRef({ x: 0, y: 0 });
  const dragStartViewportRef = useRef<Viewport>(IDENTITY_VIEWPORT);

  const [tooltip, setTooltip] = useState<NodeTooltip | null>(null);

  function clearTooltipTimer() {
    if (tooltipTimerRef.current !== null) {
      window.clearTimeout(tooltipTimerRef.current);
      tooltipTimerRef.current = null;
    }
  }

  function hideTooltip() {
    clearTooltipTimer();
    setTooltip(null);
  }

  function nodeAtClient(clientX: number, clientY: number): ElkNode | null {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    const world = screenToWorld(
      viewportRef.current,
      clientX - rect.left,
      clientY - rect.top,
    );
    return hitTestNode(rootNodeRef.current, world.x, world.y);
  }

  function edgeAtClient(
    clientX: number,
    clientY: number,
  ): ElkExtendedEdge | null {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    const world = screenToWorld(
      viewportRef.current,
      clientX - rect.left,
      clientY - rect.top,
    );
    const toleranceWorld = 6 / viewportRef.current.scale;
    return hitTestEdge(rootNodeRef.current, world.x, world.y, toleranceWorld);
  }

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
    drawGraph(
      ctx,
      rootNodeRef.current,
      resolveGraphColors(),
      labelFormatRef.current,
      hoveredIdRef.current ?? undefined,
      hoveredEdgeIdRef.current ?? undefined,
    );
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

  // A label-format change must only redraw, never re-fit the viewport (unlike
  // the rootNode effect above).
  useEffect(() => {
    labelFormatRef.current = labelFormat;
    scheduleRedraw();
    // scheduleRedraw closes only over refs; no need to re-run on its identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [labelFormat]);

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
      if (tooltipTimerRef.current !== null) {
        window.clearTimeout(tooltipTimerRef.current);
      }
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
          // #0129: on drag-over-node start node-drag instead of pan
          e.currentTarget.setPointerCapture(e.pointerId);
          isDraggingRef.current = true;
          dragStartClientRef.current = { x: e.clientX, y: e.clientY };
          dragStartViewportRef.current = viewportRef.current;
          hideTooltip();
        }}
        onPointerMove={(e) => {
          if (isDraggingRef.current) {
            const dx = e.clientX - dragStartClientRef.current.x;
            const dy = e.clientY - dragStartClientRef.current.y;
            viewportRef.current = pan(dragStartViewportRef.current, dx, dy);
            scheduleRedraw();
            return;
          }
          const prevNode = hoveredIdRef.current;
          const prevEdge = hoveredEdgeIdRef.current;

          const hit = nodeAtClient(e.clientX, e.clientY);
          if (hit) {
            e.currentTarget.style.cursor = "pointer";
            hoveredIdRef.current = hit.id;
            hoveredEdgeIdRef.current = null;

            const fullName = hit.labels?.[0]?.text ?? hit.id;
            const type = (hit as DiagramElkNode).nodeType ?? "java.package";
            const clientX = e.clientX + TOOLTIP_OFFSET_X;
            const clientY = e.clientY + TOOLTIP_OFFSET_Y;
            clearTooltipTimer();
            tooltipTimerRef.current = window.setTimeout(() => {
              setTooltip({
                shortName: shortNameOf(fullName),
                type,
                fullName,
                x: clientX,
                y: clientY,
              });
            }, TOOLTIP_HOVER_DELAY_MS);
          } else {
            hoveredIdRef.current = null;
            const edge = edgeAtClient(e.clientX, e.clientY);
            if (edge) {
              e.currentTarget.style.cursor = "pointer";
              hoveredEdgeIdRef.current = edge.id ?? null;
              hideTooltip();
            } else {
              e.currentTarget.style.cursor = "grab";
              hoveredEdgeIdRef.current = null;
              hideTooltip();
            }
          }

          if (
            hoveredIdRef.current !== prevNode ||
            hoveredEdgeIdRef.current !== prevEdge
          ) {
            scheduleRedraw();
          }
        }}
        onPointerUp={(e) => {
          const dx = e.clientX - dragStartClientRef.current.x;
          const dy = e.clientY - dragStartClientRef.current.y;
          const isClick =
            dx * dx + dy * dy < CLICK_DRAG_THRESHOLD * CLICK_DRAG_THRESHOLD;
          if (isClick) {
            // Single click on a box = in-place expand/collapse (the primary
            // gesture); double click drills/scope-replaces via onDoubleClick
            // below. No single/double timer disambiguation: the first click of
            // a double-click harmlessly toggles expand and is immediately
            // discarded when the drill unmounts the compound view (a brief,
            // consequence-free flash) — this keeps zero latency on every single
            // click, which matters for the primary gesture.
            const nodeHit = nodeAtClient(e.clientX, e.clientY);
            if (nodeHit) {
              onNodeToggleExpand?.(nodeHit.id);
            } else if (onEdgeActivate) {
              const edge = edgeAtClient(e.clientX, e.clientY);
              if (edge?.sources?.[0] && edge.targets?.[0]) {
                onEdgeActivate(edge.sources[0], edge.targets[0]);
              }
            }
          }
          isDraggingRef.current = false;
          e.currentTarget.releasePointerCapture(e.pointerId);
        }}
        onDoubleClick={(e) => {
          const nodeHit = nodeAtClient(e.clientX, e.clientY);
          if (nodeHit) {
            onNodeActivate?.(
              nodeHit.id,
              nodeHit.labels?.[0]?.text ?? nodeHit.id,
            );
          }
        }}
        onPointerCancel={(e) => {
          isDraggingRef.current = false;
          e.currentTarget.releasePointerCapture(e.pointerId);
          hideTooltip();
          hoveredEdgeIdRef.current = null;
        }}
        onPointerLeave={() => {
          hideTooltip();
          if (
            hoveredIdRef.current !== null ||
            hoveredEdgeIdRef.current !== null
          ) {
            hoveredIdRef.current = null;
            hoveredEdgeIdRef.current = null;
            scheduleRedraw();
          }
        }}
      />
      <div className="absolute top-2 right-2 z-10 flex gap-1">
        <DependencyDiagramControls
          onFit={handleFit}
          onZoomIn={handleZoomIn}
          onZoomOut={handleZoomOut}
        />
      </div>
      {tooltip && (
        <NodeInfoTooltip
          x={tooltip.x}
          y={tooltip.y}
          shortName={tooltip.shortName}
          type={tooltip.type}
          fullName={tooltip.fullName}
        />
      )}
    </div>
  );
}
