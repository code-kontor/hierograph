import { useEffect, useRef } from "react";

import { drawDsm } from "./drawDsm";

type DsmCanvasProps = {
  labels: { id: string; text: string }[];
  cells: { row: number; column: number; value: number }[];
  sccs: { nodePositions: number[] }[];
};

export function DsmCanvas({ labels, cells, sccs }: DsmCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (canvasRef.current) {
      drawDsm(canvasRef.current, { labels, cells, sccs });
    }
  }, [labels, cells, sccs]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const observer = new ResizeObserver(() => {
      if (canvasRef.current) {
        drawDsm(canvasRef.current, { labels, cells, sccs });
      }
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, [labels, cells, sccs]);

  return (
    <div ref={containerRef}>
      <canvas ref={canvasRef} />
    </div>
  );
}
