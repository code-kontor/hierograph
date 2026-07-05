import { useEffect, useMemo, useRef, useState } from "react";

import { NodeInfoTooltip } from "@/graph/NodeInfoTooltip";
import { formatNodeLabel, type NodeLabelFormat } from "@/graph/nodeLabel";

import { drawDsm, drawDsmOverlay } from "./drawDsm";
import {
  BOX_SIZE,
  buildCellSelection,
  buildMatrixElements,
  computeCellPosition,
  DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
  DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
  type DsmCellSelection,
  type DsmMarkerSizes,
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
};

const MIN_SIDE_MARKER = 24;

export function DsmCanvas({
  labels,
  cells,
  sccs,
  labelFormat,
  showDiagonal,
  onHoverCell,
  onSelectCell,
}: DsmCanvasProps) {
  const baseCanvasRef = useRef<HTMLCanvasElement>(null);
  const overlayCanvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const [markerSizes, setMarkerSizes] = useState<DsmMarkerSizes>({
    verticalSideMarkerWidth: DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
    horizontalSideMarkerHeight: DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
  });
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

  const format = useMemo(
    () => (text: string, type?: string) =>
      formatNodeLabel(text, labelFormat, type),
    [labelFormat],
  );

  const matrixElements = useMemo(() => buildMatrixElements(cells), [cells]);

  // Tooltip timer cleanup on unmount
  useEffect(() => {
    return () => {
      if (tooltipTimerRef.current !== null) {
        window.clearTimeout(tooltipTimerRef.current);
      }
    };
  }, []);

  // Base canvas draw
  useEffect(() => {
    if (baseCanvasRef.current) {
      drawDsm(
        baseCanvasRef.current,
        { labels, cells, sccs },
        markerSizes,
        format,
        showDiagonal,
      );
    }
  }, [labels, cells, sccs, markerSizes, format, showDiagonal]);

  // Overlay canvas draw
  useEffect(() => {
    if (overlayCanvasRef.current) {
      drawDsmOverlay(
        overlayCanvasRef.current,
        { labels, sccs, hover, headerHover, selected },
        markerSizes,
        format,
      );
    }
  }, [labels, sccs, hover, headerHover, selected, markerSizes, format]);

  // ResizeObserver safeguard
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const observer = new ResizeObserver(() => {
      if (baseCanvasRef.current) {
        drawDsm(
          baseCanvasRef.current,
          { labels, cells, sccs },
          markerSizes,
          format,
          showDiagonal,
        );
      }
      if (overlayCanvasRef.current) {
        drawDsmOverlay(
          overlayCanvasRef.current,
          { labels, sccs, hover, headerHover, selected },
          markerSizes,
          format,
        );
      }
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, [
    labels,
    cells,
    sccs,
    markerSizes,
    hover,
    headerHover,
    selected,
    format,
    showDiagonal,
  ]);

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
          );
          const changed =
            x !== hover?.x ||
            y !== hover?.y ||
            (x === undefined) !== (hover === null);
          if (changed) {
            const next = x !== undefined && y !== undefined ? { x, y } : null;
            setHover(next);
            onHoverCell?.(buildCellSelection(x, y, labels, matrixElements));
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
                (offsetY - markerSizes.horizontalSideMarkerHeight) / BOX_SIZE,
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
                (offsetX - markerSizes.verticalSideMarkerWidth) / BOX_SIZE,
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
            );
            const next = x !== undefined && y !== undefined ? { x, y } : null;
            setSelected(next);
            onSelectCell?.(buildCellSelection(x, y, labels, matrixElements));
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
