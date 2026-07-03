import { useEffect, useMemo, useRef, useState } from "react";

import {
  BOX_SIZE,
  DEFAULT_HORIZONTAL_SIDE_MARKER_HEIGHT,
  DEFAULT_VERTICAL_SIDE_MARKER_WIDTH,
  drawDsm,
  drawDsmOverlay,
  type DsmMarkerSizes,
  SEP_SIZE,
} from "./drawDsm";
import { DsmTooltip } from "./DsmTooltip";
import { formatLabel, type LabelFormat } from "./labelFormat";

export type DsmCellSelection = {
  sourceNodeId: string;
  targetNodeId: string;
  value: number;
  sourceLabel: { id: string; text: string };
  targetLabel: { id: string; text: string };
};

type DsmCanvasProps = {
  labels: { id: string; text: string }[];
  cells: { row: number; column: number; value: number }[];
  sccs: { nodePositions: number[] }[];
  labelFormat: LabelFormat;
  onHoverCell?: (selection: DsmCellSelection | undefined) => void;
  onSelectCell?: (selection: DsmCellSelection | undefined) => void;
};

const MIN_SIDE_MARKER = 24;

export function DsmCanvas({
  labels,
  cells,
  sccs,
  labelFormat,
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

  const [tooltip, setTooltip] = useState<{
    text: string;
    x: number;
    y: number;
  } | null>(null);

  const mouseDownRef = useRef(false);
  const verticalResizeRef = useRef(false);
  const horizontalResizeRef = useRef(false);

  const format = useMemo(
    () => (text: string) => formatLabel(text, labelFormat),
    [labelFormat],
  );

  const matrixElements = useMemo(() => {
    const elements: { row: number; column: number; value: number }[][] = [];
    for (const cell of cells) {
      if (!elements[cell.column]) {
        elements[cell.column] = [];
      }
      elements[cell.column][cell.row] = cell;
    }
    return elements;
  }, [cells]);

  function computePosition(
    offsetX: number,
    offsetY: number,
  ): { x: number | undefined; y: number | undefined } {
    let x: number | undefined = Math.floor(
      (offsetX - markerSizes.verticalSideMarkerWidth) / BOX_SIZE,
    );
    let y: number | undefined = Math.floor(
      (offsetY - markerSizes.horizontalSideMarkerHeight) / BOX_SIZE,
    );
    if (x < 0 || x >= labels.length) x = undefined;
    if (y < 0 || y >= labels.length) y = undefined;
    return { x, y };
  }

  function buildSelection(
    x: number | undefined,
    y: number | undefined,
  ): DsmCellSelection | undefined {
    if (x === undefined || y === undefined) return undefined;
    return {
      sourceNodeId: labels[y].id,
      targetNodeId: labels[x].id,
      value: matrixElements[y]?.[x]?.value ?? 0,
      sourceLabel: { id: labels[y].id, text: labels[y].text },
      targetLabel: { id: labels[x].id, text: labels[x].text },
    };
  }

  // Base canvas draw
  useEffect(() => {
    if (baseCanvasRef.current) {
      drawDsm(
        baseCanvasRef.current,
        { labels, cells, sccs },
        markerSizes,
        format,
      );
    }
  }, [labels, cells, sccs, markerSizes, format]);

  // Overlay canvas draw
  useEffect(() => {
    if (overlayCanvasRef.current) {
      drawDsmOverlay(
        overlayCanvasRef.current,
        { labels, sccs, hover, selected },
        markerSizes,
        format,
      );
    }
  }, [labels, sccs, hover, selected, markerSizes, format]);

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
        );
      }
      if (overlayCanvasRef.current) {
        drawDsmOverlay(
          overlayCanvasRef.current,
          { labels, sccs, hover, selected },
          markerSizes,
          format,
        );
      }
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, [labels, cells, sccs, markerSizes, hover, selected, format]);

  return (
    <div ref={containerRef} className="relative inline-block">
      <canvas ref={baseCanvasRef} className="block" />
      <canvas
        ref={overlayCanvasRef}
        className="absolute top-0 left-0"
        onMouseDown={() => {
          mouseDownRef.current = true;
        }}
        onMouseMove={(e) => {
          const offsetX = e.nativeEvent.offsetX;
          const offsetY = e.nativeEvent.offsetY;

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

          const { x, y } = computePosition(offsetX, offsetY);
          const changed =
            x !== hover?.x ||
            y !== hover?.y ||
            (x === undefined) !== (hover === null);
          if (changed) {
            const next = x !== undefined && y !== undefined ? { x, y } : null;
            setHover(next);
            onHoverCell?.(buildSelection(x, y));
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
                setTooltip({
                  text: labels[rowIndex].text,
                  x: offsetX,
                  y: offsetY,
                });
              } else {
                setTooltip(null);
              }
            } else if (inHorizontalBand) {
              const colIndex = Math.floor(
                (offsetX - markerSizes.verticalSideMarkerWidth) / BOX_SIZE,
              );
              if (colIndex >= 0 && colIndex < labels.length) {
                setTooltip({
                  text: labels[colIndex].text,
                  x: offsetX,
                  y: offsetY,
                });
              } else {
                setTooltip(null);
              }
            } else {
              setTooltip(null);
            }
          }
        }}
        onMouseUp={(e) => {
          mouseDownRef.current = false;
          if (!verticalResizeRef.current && !horizontalResizeRef.current) {
            const { x, y } = computePosition(
              e.nativeEvent.offsetX,
              e.nativeEvent.offsetY,
            );
            const next = x !== undefined && y !== undefined ? { x, y } : null;
            setSelected(next);
            onSelectCell?.(buildSelection(x, y));
          }
        }}
        onMouseLeave={() => {
          mouseDownRef.current = false;
          setHover(null);
          setTooltip(null);
          onHoverCell?.(undefined);
        }}
      />
      {tooltip && (
        <DsmTooltip text={tooltip.text} x={tooltip.x} y={tooltip.y} />
      )}
    </div>
  );
}
