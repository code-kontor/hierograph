import type { ElkNode } from "elkjs/lib/elk.bundled.js";
import { useEffect, useRef, useState } from "react";

import { resolveGraphColors } from "./colorScheme";
import { setupCanvas } from "./dpiFixer";
import { drawGraph } from "./drawGraph";

type DependencyDiagramCanvasProps = { rootNode: ElkNode };

export function DependencyDiagramCanvas({
  rootNode,
}: DependencyDiagramCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [redrawTick, setRedrawTick] = useState(0);

  useEffect(() => {
    const container = containerRef.current?.parentElement;
    if (!container) return;

    const observer = new ResizeObserver(() => {
      setRedrawTick((tick) => tick + 1);
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const width = rootNode.width;
    const height = rootNode.height;
    if (!canvas || width === undefined || height === undefined) return;

    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    canvas.width = width;
    canvas.height = height;
    setupCanvas(canvas, ctx);
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    drawGraph(ctx, rootNode, resolveGraphColors());
    // redrawTick: ResizeObserver-triggered redraw (e.g. after a devicePixelRatio
    // change); the layout itself is content-, not container-driven.
  }, [rootNode, redrawTick]);

  return (
    <div ref={containerRef} className="relative inline-block">
      <canvas ref={canvasRef} className="block" />
    </div>
  );
}
