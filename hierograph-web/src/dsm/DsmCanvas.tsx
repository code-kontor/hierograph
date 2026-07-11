import { useEffect, useRef, useState } from "react";

import { NodeInfoTooltip } from "@/graph/NodeInfoTooltip";
import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";

import { drawDsm, drawDsmOverlay } from "./drawDsm";
import {
  buildCellSelection,
  buildMatrixElements,
  computeCellPosition,
  DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
  DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
  type DsmCellSelection,
  type DsmMarkerSizes,
  MAX_BOX_SIZE,
  MIN_BOX_SIZE,
  SEP_SIZE,
} from "./dsmModel";

export type { DsmCellSelection };

// Row / column side markers are graph nodes; show the same hover card as the tree.
function buildLabelTooltip(
  label: { text: string; type?: string },
  x: number,
  y: number,
) {
  return {
    shortName: label.text.split(".").pop() ?? label.text,
    type: label.type ?? "java.package",
    fullName: label.text,
    x,
    y,
  };
}

type DsmCanvasProps = {
  labels: { id: string; text: string; type?: string }[];
  cells: { row: number; column: number; value: number }[];
  sccs: { nodePositions: number[] }[];
  labelFormat: NodeLabelFormat;
  showDiagonal: boolean;
  onHoverCell?: (selection: DsmCellSelection | undefined) => void;
  onSelectCell?: (selection: DsmCellSelection | undefined) => void;
  // Required (no destructuring defaults): default values in destructured
  // props currently make the React Compiler bail out on the whole component
  // (babel-plugin-react-compiler 1.0.0 on Babel 8), and this component needs
  // compilation — the draw effects depend on stable identities.
  fitToWindow: boolean;
  cellSize: number;
};

const MIN_SIDE_MARKER = 24;

function computeBoxSize(
  fitToWindow: boolean,
  cellSize: number,
  availableSize: { width: number; height: number } | null,
  labelCount: number,
  markerSizes: DsmMarkerSizes,
): number {
  if (!fitToWindow || availableSize === null || labelCount === 0) {
    return Math.min(Math.max(cellSize, MIN_BOX_SIZE), MAX_BOX_SIZE);
  }
  const availW = availableSize.width - markerSizes.verticalSideMarkerWidth;
  const availH = availableSize.height - markerSizes.horizontalSideMarkerHeight;
  const fit = Math.floor(Math.min(availW / labelCount, availH / labelCount));
  return Math.min(Math.max(fit, MIN_BOX_SIZE), MAX_BOX_SIZE);
}

export function DsmCanvas({
  labels,
  cells,
  sccs,
  labelFormat,
  showDiagonal,
  onHoverCell,
  onSelectCell,
  fitToWindow,
  cellSize,
}: DsmCanvasProps) {
  const baseCanvasRef = useRef<HTMLCanvasElement>(null);
  const overlayCanvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const [markerSizes, setMarkerSizes] = useState<DsmMarkerSizes>({
    verticalSideMarkerWidth: DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
    horizontalSideMarkerHeight: DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
  });
  const [availableSize, setAvailableSize] = useState<{
    width: number;
    height: number;
  } | null>(null);
  const [hover, setHover] = useState<{ x: number; y: number } | null>(null);
  const [selected, setSelected] = useState<{ x: number; y: number } | null>(
    null,
  );
  const [headerHover, setHeaderHover] = useState<{
    axis: "row" | "col";
    index: number;
  } | null>(null);

  const [tooltip, setTooltip] = useState<{
    shortName: string;
    type: string;
    fullName: string;
    x: number;
    y: number;
  } | null>(null);

  const mouseDownRef = useRef(false);
  const verticalResizeRef = useRef(false);
  const horizontalResizeRef = useRef(false);
  const tooltipTimerRef = useRef<number | null>(null);

  // Referentially stable per its inputs via the React Compiler — the draw
  // effects below depend on it.
  const boxSize = computeBoxSize(
    fitToWindow,
    cellSize,
    availableSize,
    labels.length,
    markerSizes,
  );

  // Tooltip timer cleanup on unmount
  useEffect(() => {
    return () => {
      if (tooltipTimerRef.current !== null) {
        window.clearTimeout(tooltipTimerRef.current);
      }
    };
  }, []);

  // Base canvas draw. The label formatter is created inside the effect so the
  // dependency is the reactive input (labelFormat), not a function identity.
  useEffect(() => {
    if (baseCanvasRef.current) {
      const format = (text: string, type?: string) =>
        formatNodeLabel(text, labelFormat, type);
      drawDsm(
        baseCanvasRef.current,
        { labels, cells, sccs },
        markerSizes,
        format,
        showDiagonal,
        boxSize,
      );
    }
  }, [labels, cells, sccs, markerSizes, labelFormat, showDiagonal, boxSize]);

  // Overlay canvas draw
  useEffect(() => {
    if (overlayCanvasRef.current) {
      const format = (text: string, type?: string) =>
        formatNodeLabel(text, labelFormat, type);
      drawDsmOverlay(
        overlayCanvasRef.current,
        { labels, sccs, hover, headerHover, selected },
        markerSizes,
        format,
        boxSize,
      );
    }
  }, [
    labels,
    sccs,
    hover,
    headerHover,
    selected,
    markerSizes,
    labelFormat,
    boxSize,
  ]);

  // Measure the outer scroll container so "fit to window" can compute a box size.
  useEffect(() => {
    const container = containerRef.current?.parentElement;
    if (!container) return;

    const measure = () => {
      setAvailableSize({
        width: container.clientWidth,
        height: container.clientHeight,
      });
    };
    measure();

    const observer = new ResizeObserver(measure);
    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  return (
    <div ref={containerRef} className="relative inline-block">
      <canvas ref={baseCanvasRef} className="block" />
      <canvas
        ref={overlayCanvasRef}
        data-testid="dsm-overlay-canvas"
        className="absolute top-0 left-0"
        onMouseDown={() => {
          mouseDownRef.current = true;
        }}
        onMouseMove={(e) => {
          const offsetX = e.nativeEvent.offsetX;
          const offsetY = e.nativeEvent.offsetY;
          // Viewport coords for the (fixed-positioned, portalled) hover card.
          const clientX = e.clientX + 18;
          const clientY = e.clientY + 20;

          if (
            mouseDownRef.current &&
            (verticalResizeRef.current || horizontalResizeRef.current)
          ) {
            setMarkerSizes((prev) => ({
              verticalSideMarkerWidth: verticalResizeRef.current
                ? Math.max(offsetX, MIN_SIDE_MARKER)
                : prev.verticalSideMarkerWidth,
              horizontalSideMarkerHeight: horizontalResizeRef.current
                ? Math.max(offsetY, MIN_SIDE_MARKER)
                : prev.horizontalSideMarkerHeight,
            }));
            return;
          }

          const inVerticalResizeZone =
            offsetX > markerSizes.verticalSideMarkerWidth - 2 * SEP_SIZE &&
            offsetX < markerSizes.verticalSideMarkerWidth + SEP_SIZE;
          const inHorizontalResizeZone =
            offsetY > markerSizes.horizontalSideMarkerHeight - 2 * SEP_SIZE &&
            offsetY < markerSizes.horizontalSideMarkerHeight + SEP_SIZE;

          verticalResizeRef.current = inVerticalResizeZone;
          horizontalResizeRef.current = inHorizontalResizeZone;

          const overlayEl = overlayCanvasRef.current;
          if (overlayEl) {
            if (inVerticalResizeZone && inHorizontalResizeZone) {
              overlayEl.style.cursor = "nwse-resize";
            } else if (inHorizontalResizeZone) {
              overlayEl.style.cursor = "ns-resize";
            } else if (inVerticalResizeZone) {
              overlayEl.style.cursor = "ew-resize";
            } else {
              overlayEl.style.cursor = "";
            }
          }

          const { x, y } = computeCellPosition(
            offsetX,
            offsetY,
            markerSizes,
            labels.length,
            boxSize,
          );
          const changed =
            x !== hover?.x ||
            y !== hover?.y ||
            (x === undefined) !== (hover === null);
          if (changed) {
            const next = x !== undefined && y !== undefined ? { x, y } : null;
            setHover(next);
            onHoverCell?.(
              buildCellSelection(x, y, labels, buildMatrixElements(cells)),
            );
          }

          if (!mouseDownRef.current) {
            const inVerticalBand =
              offsetX < markerSizes.verticalSideMarkerWidth &&
              offsetY >= markerSizes.horizontalSideMarkerHeight;
            const inHorizontalBand =
              offsetY < markerSizes.horizontalSideMarkerHeight &&
              offsetX >= markerSizes.verticalSideMarkerWidth;

            if (inVerticalBand) {
              const rowIndex = Math.floor(
                (offsetY - markerSizes.horizontalSideMarkerHeight) / boxSize,
              );
              if (rowIndex >= 0 && rowIndex < labels.length) {
                setHeaderHover({ axis: "row", index: rowIndex });
                if (tooltipTimerRef.current !== null) {
                  window.clearTimeout(tooltipTimerRef.current);
                }
                tooltipTimerRef.current = window.setTimeout(() => {
                  setTooltip(
                    buildLabelTooltip(labels[rowIndex], clientX, clientY),
                  );
                }, 300);
              } else {
                setHeaderHover(null);
                if (tooltipTimerRef.current !== null) {
                  window.clearTimeout(tooltipTimerRef.current);
                  tooltipTimerRef.current = null;
                }
                setTooltip(null);
              }
            } else if (inHorizontalBand) {
              const colIndex = Math.floor(
                (offsetX - markerSizes.verticalSideMarkerWidth) / boxSize,
              );
              if (colIndex >= 0 && colIndex < labels.length) {
                setHeaderHover({ axis: "col", index: colIndex });
                if (tooltipTimerRef.current !== null) {
                  window.clearTimeout(tooltipTimerRef.current);
                }
                tooltipTimerRef.current = window.setTimeout(() => {
                  setTooltip(
                    buildLabelTooltip(labels[colIndex], clientX, clientY),
                  );
                }, 300);
              } else {
                setHeaderHover(null);
                if (tooltipTimerRef.current !== null) {
                  window.clearTimeout(tooltipTimerRef.current);
                  tooltipTimerRef.current = null;
                }
                setTooltip(null);
              }
            } else {
              setHeaderHover(null);
              if (tooltipTimerRef.current !== null) {
                window.clearTimeout(tooltipTimerRef.current);
                tooltipTimerRef.current = null;
              }
              setTooltip(null);
            }
          }
        }}
        onMouseUp={(e) => {
          mouseDownRef.current = false;
          if (!verticalResizeRef.current && !horizontalResizeRef.current) {
            const { x, y } = computeCellPosition(
              e.nativeEvent.offsetX,
              e.nativeEvent.offsetY,
              markerSizes,
              labels.length,
              boxSize,
            );
            const next = x !== undefined && y !== undefined ? { x, y } : null;
            setSelected(next);
            onSelectCell?.(
              buildCellSelection(x, y, labels, buildMatrixElements(cells)),
            );
          }
        }}
        onMouseLeave={() => {
          mouseDownRef.current = false;
          setHover(null);
          setHeaderHover(null);
          if (tooltipTimerRef.current !== null) {
            window.clearTimeout(tooltipTimerRef.current);
            tooltipTimerRef.current = null;
          }
          setTooltip(null);
          onHoverCell?.(undefined);
        }}
      />
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
