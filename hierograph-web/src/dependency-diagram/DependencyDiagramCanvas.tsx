import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { useEffect, useRef, useState } from "react";

import { NodeInfoTooltip } from "@/graph/NodeInfoTooltip";
import { type NodeLabelFormat, shortNameOf } from "@/graph/nodeLabel";

import { resolveGraphColors } from "./colorScheme";
import { DependencyDiagramControls } from "./DependencyDiagramControls";
import { setupCanvas } from "./dpiFixer";
import { drawGraph } from "./drawGraph";
import { collectWorldGraph } from "./edgeHitTest";
import type { DiagramElkNode } from "./elkLayout";
import { hitTestNode } from "./hitTest";
import { straightenIncidentEdges } from "./nodeDrag";
import { NodeToolbar } from "./NodeToolbar";
import { type PointerHit, resolvePointerHit } from "./pointerHitTest";
import {
  FIT_PADDING,
  fitToView,
  pan,
  screenToWorld,
  type Viewport,
  worldToScreen,
  zoomAt,
} from "./viewport";

const ZOOM_SENSITIVITY = 0.0015;
const BUTTON_ZOOM_FACTOR = 1.2;
const CLICK_DRAG_THRESHOLD = 4;
const IDENTITY_VIEWPORT: Viewport = { scale: 1, translateX: 0, translateY: 0 };
const TOOLTIP_HOVER_DELAY_MS = 300;
const TOOLTIP_OFFSET_X = 18;
const TOOLTIP_OFFSET_Y = 20;
const TOOLBAR_HIDE_DELAY_MS = 120;
const EMPTY_SET: ReadonlySet<string> = new Set();

type DependencyDiagramCanvasProps = {
  rootNode: ElkNode;
  labelFormat: NodeLabelFormat;
  expandedIds?: ReadonlySet<string>;
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

type NodeToolbarState = {
  nodeId: string;
  fqn: string;
  isExpanded: boolean;
  left: number;
  top: number;
};

export function DependencyDiagramCanvas({
  rootNode,
  labelFormat,
  expandedIds,
  onNodeActivate,
  onNodeToggleExpand,
  onEdgeActivate,
}: DependencyDiagramCanvasProps) {
  const expanded = expandedIds ?? EMPTY_SET;
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
  const toolbarHideTimerRef = useRef<number | null>(null);
  // Which node the hover toolbar is currently shown for (null = hidden). Kept as
  // a ref so the pointer-move handler can decide whether to (re)create the
  // toolbar independently of the hover-highlight redraw bookkeeping.
  const toolbarNodeIdRef = useRef<string | null>(null);

  const dragModeRef = useRef<"none" | "pan" | "node">("none");
  const dragStartClientRef = useRef({ x: 0, y: 0 });
  const dragStartViewportRef = useRef<Viewport>(IDENTITY_VIEWPORT);
  const dragNodeRef = useRef<ElkNode | null>(null);
  const dragNodeStartRef = useRef({ x: 0, y: 0 });

  const [tooltip, setTooltip] = useState<NodeTooltip | null>(null);
  const [toolbar, setToolbar] = useState<NodeToolbarState | null>(null);

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

  function clearToolbarHideTimer() {
    if (toolbarHideTimerRef.current !== null) {
      window.clearTimeout(toolbarHideTimerRef.current);
      toolbarHideTimerRef.current = null;
    }
  }

  function hideToolbar() {
    clearToolbarHideTimer();
    toolbarNodeIdRef.current = null;
    setToolbar(null);
  }

  // Delays hiding the toolbar so the pointer can travel from the box to the
  // floating toolbar (a separate DOM overlay) without it disappearing first.
  function scheduleToolbarHide() {
    clearToolbarHideTimer();
    toolbarHideTimerRef.current = window.setTimeout(() => {
      toolbarNodeIdRef.current = null;
      setToolbar(null);
    }, TOOLBAR_HIDE_DELAY_MS);
  }

  // Entering the toolbar means leaving the canvas, which fires the canvas's
  // pointer-leave and clears the node's hover highlight. Re-assert it so the box
  // the toolbar belongs to keeps its highlight while the toolbar is hovered.
  function keepToolbarNodeHovered() {
    clearToolbarHideTimer();
    const nodeId = toolbarNodeIdRef.current;
    if (nodeId !== null && hoveredIdRef.current !== nodeId) {
      hoveredIdRef.current = nodeId;
      scheduleRedraw();
    }
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

  // Resolves what the pointer is over (node vs. edge) with edge-over-container
  // precedence — see resolvePointerHit. Converts client -> world first, like
  // nodeAtClient.
  function resolveHitAtClient(
    clientX: number,
    clientY: number,
  ): PointerHit | null {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    const world = screenToWorld(
      viewportRef.current,
      clientX - rect.left,
      clientY - rect.top,
    );
    const toleranceWorld = 6 / viewportRef.current.scale;
    return resolvePointerHit(
      rootNodeRef.current,
      world.x,
      world.y,
      toleranceWorld,
    );
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
      setToolbar(null);
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
      if (toolbarHideTimerRef.current !== null) {
        window.clearTimeout(toolbarHideTimerRef.current);
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
          e.currentTarget.setPointerCapture(e.pointerId);
          hideTooltip();
          hideToolbar();
          dragStartClientRef.current = { x: e.clientX, y: e.clientY };

          const hit = nodeAtClient(e.clientX, e.clientY);
          if (hit && hit.x !== undefined && hit.y !== undefined) {
            dragModeRef.current = "node";
            dragNodeRef.current = hit;
            dragNodeStartRef.current = { x: hit.x, y: hit.y };
          } else {
            dragModeRef.current = "pan";
            dragStartViewportRef.current = viewportRef.current;
          }
        }}
        onPointerMove={(e) => {
          if (dragModeRef.current === "pan") {
            hideToolbar();
            const dx = e.clientX - dragStartClientRef.current.x;
            const dy = e.clientY - dragStartClientRef.current.y;
            viewportRef.current = pan(dragStartViewportRef.current, dx, dy);
            scheduleRedraw();
            return;
          }
          if (dragModeRef.current === "node") {
            hideToolbar();
            const node = dragNodeRef.current;
            if (!node) return;
            const dx = e.clientX - dragStartClientRef.current.x;
            const dy = e.clientY - dragStartClientRef.current.y;
            const s = viewportRef.current.scale;
            node.x = dragNodeStartRef.current.x + dx / s;
            node.y = dragNodeStartRef.current.y + dy / s;
            straightenIncidentEdges(rootNodeRef.current, node.id);
            scheduleRedraw();
            return;
          }
          const prevNode = hoveredIdRef.current;
          const prevEdge = hoveredEdgeIdRef.current;

          const hit = resolveHitAtClient(e.clientX, e.clientY);
          if (hit?.kind === "node") {
            const node = hit.node;
            e.currentTarget.style.cursor = "pointer";
            hoveredIdRef.current = node.id;
            hoveredEdgeIdRef.current = null;

            const fullName = node.labels?.[0]?.text ?? node.id;
            const type = (node as DiagramElkNode).nodeType ?? "java.package";
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

            clearToolbarHideTimer();
            if (toolbarNodeIdRef.current !== node.id) {
              const box = collectWorldGraph(rootNodeRef.current).nodes.get(
                node.id,
              );
              const canvas = canvasRef.current;
              if (box && canvas) {
                const rect = canvas.getBoundingClientRect();
                // Anchor at the box's top-right corner; NodeToolbar draws itself
                // inside the box (its right edge on this point), so hovering it
                // never crosses empty canvas and keeps the box highlighted.
                const screen = worldToScreen(
                  viewportRef.current,
                  box.x + box.width,
                  box.y,
                );
                toolbarNodeIdRef.current = node.id;
                setToolbar({
                  nodeId: node.id,
                  fqn: fullName,
                  isExpanded: expanded.has(node.id),
                  left: rect.left + screen.x,
                  top: rect.top + screen.y,
                });
              }
            }
          } else if (hit?.kind === "edge") {
            e.currentTarget.style.cursor = "pointer";
            hoveredIdRef.current = null;
            hoveredEdgeIdRef.current = hit.edge.id ?? null;
            hideTooltip();
            scheduleToolbarHide();
          } else {
            e.currentTarget.style.cursor = "grab";
            hoveredIdRef.current = null;
            hoveredEdgeIdRef.current = null;
            hideTooltip();
            scheduleToolbarHide();
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
          // A click on a box is inert (no toggle/drill) — those live on the
          // hover toolbar now. A click activates the edge under the pointer, if
          // any — including edges nested inside an expanded container, where the
          // press starts as a container "node" drag but the resolved hit is the
          // edge (leaf boxes still resolve to the node and stay inert).
          if (isClick && onEdgeActivate) {
            const hit = resolveHitAtClient(e.clientX, e.clientY);
            if (
              hit?.kind === "edge" &&
              hit.edge.sources?.[0] &&
              hit.edge.targets?.[0]
            ) {
              onEdgeActivate(hit.edge.sources[0], hit.edge.targets[0]);
            }
          }
          dragModeRef.current = "none";
          dragNodeRef.current = null;
          e.currentTarget.releasePointerCapture(e.pointerId);
        }}
        onPointerCancel={(e) => {
          dragModeRef.current = "none";
          dragNodeRef.current = null;
          e.currentTarget.releasePointerCapture(e.pointerId);
          hideTooltip();
          hideToolbar();
          hoveredEdgeIdRef.current = null;
        }}
        onPointerLeave={() => {
          hideTooltip();
          scheduleToolbarHide();
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
      {toolbar && (
        <NodeToolbar
          left={toolbar.left}
          top={toolbar.top}
          nodeId={toolbar.nodeId}
          fqn={toolbar.fqn}
          isExpanded={toolbar.isExpanded}
          onToggleExpand={(id) => onNodeToggleExpand?.(id)}
          onDrill={(id, label) => onNodeActivate?.(id, label)}
          onPointerEnter={keepToolbarNodeHovered}
          onPointerLeave={scheduleToolbarHide}
        />
      )}
    </div>
  );
}
