// The geometry of the dependency arcs beside the hierarchy tree: bracket
// shapes between rows, on the right of the anchor column (away from the tree)
// for a dependency on a row below (with the tree's order), on the left, between
// the anchors and the labels, for one on a row above.

export type ArcRow = { id: string; y: number };

export type ArcDependency = { from: string; to: string; weight: number };

export type Arc = ArcDependency & {
  direction: "down" | "up";
  path: string;
  // The vertical distance, to draw long arcs first and short ones on top.
  span: number;
};

export type ArcLayout = {
  // The x of the anchor column.
  anchorX: number;
  // How far an arc may reach out to the right (down) and to the left (up).
  maxDown: number;
  maxUp: number;
};

const MIN_OFFSET = 8;
const OFFSET_PER_PX = 0.15;
const STUB = 4;
const MAX_CORNER = 8;

export function layoutArcs(
  rows: ArcRow[],
  dependencies: ArcDependency[],
  layout: ArcLayout,
): Arc[] {
  const ys = new Map(rows.map((row) => [row.id, row.y]));
  const arcs: Arc[] = [];
  for (const dependency of dependencies) {
    const y1 = ys.get(dependency.from);
    const y2 = ys.get(dependency.to);
    if (y1 === undefined || y2 === undefined || y1 === y2) continue;
    const direction = y2 > y1 ? "down" : "up";
    const span = Math.abs(y2 - y1);
    const max = direction === "down" ? layout.maxDown : layout.maxUp;
    const offset = Math.min(max, MIN_OFFSET + span * OFFSET_PER_PX);
    arcs.push({
      ...dependency,
      direction,
      span,
      path: bracketPath(
        layout.anchorX,
        y1,
        y2,
        direction === "down" ? 1 : -1,
        offset,
      ),
    });
  }
  return arcs.sort((a, b) => b.span - a.span);
}

// From (x, y1) out by `offset` (to the left for side -1, the right for +1),
// a rounded corner, vertically to y2, a rounded corner, back to (x, y2).
export function bracketPath(
  x: number,
  y1: number,
  y2: number,
  side: -1 | 1,
  offset: number,
): string {
  const outer = x + side * offset;
  const vertical = y2 > y1 ? 1 : -1;
  // Small rounded corners, keeping a short straight stub at the anchor: the
  // arc reads as a bracket; only a very short span curves all the way.
  const r = Math.max(
    0,
    Math.min(MAX_CORNER, offset - STUB, Math.abs(y2 - y1) / 2),
  );
  return [
    `M ${x} ${y1}`,
    `H ${outer - side * r}`,
    `Q ${outer} ${y1} ${outer} ${y1 + vertical * r}`,
    `V ${y2 - vertical * r}`,
    `Q ${outer} ${y2} ${outer - side * r} ${y2}`,
    `H ${x}`,
  ].join(" ");
}
