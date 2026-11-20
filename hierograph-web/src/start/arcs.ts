// The geometry of the dependency arcs beside the hierarchy tree: bracket
// shapes between rows, on the right of the anchor column (away from the tree)
// for a dependency on a row below (with the tree's order), on the left, between
// the anchors and the labels, for one on a row above. The two sides start a
// gap apart, so the arcs of one side don't run into the other's. An arrowhead
// at the anchor of the row depended on gives each arc its direction.

export type ArcRow = { id: string; y: number };

export type ArcDependency = { from: string; to: string; weight: number };

export type Arc = ArcDependency & {
  direction: "down" | "up";
  path: string;
  // The arrowhead at the end of the path, on the row depended on.
  head: string;
  // The vertical distance, to draw long arcs first and short ones on top.
  span: number;
};

export type ArcLayout = {
  // The x of the anchor column: the middle of the gap between the anchors of
  // the arcs up (on its left) and down (on its right).
  anchorX: number;
  gap: number;
  // How far an arc may reach out to the right (down) and to the left (up).
  maxDown: number;
  maxUp: number;
};

// A visible row by its lineage: its own id, then its ancestors' ids.
export type LineageRow = { id: string; lineage: string[] };

// The dependencies between the visible rows that close a cycle of siblings
// (`members`, by id): those between rows inside two different members, each
// row counted to the member that is it or contains it. Every one of them is
// on the cycle, since the members all reach each other.
export function cycleDependencies(
  dependencies: ArcDependency[],
  members: string[],
  rows: LineageRow[],
): ArcDependency[] {
  const cycle = new Set(members);
  const memberOf = new Map(
    rows.map((row) => [row.id, row.lineage.find((id) => cycle.has(id))]),
  );
  return dependencies.filter((dependency) => {
    const from = memberOf.get(dependency.from);
    const to = memberOf.get(dependency.to);
    return from !== undefined && to !== undefined && from !== to;
  });
}

const MIN_OFFSET = 8;
const OFFSET_PER_PX = 0.15;
const STUB = 4;
const MAX_CORNER = 8;
const HEAD_LENGTH = 4.5;
const HEAD_HALF_WIDTH = 2.5;

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
    const side = direction === "down" ? 1 : -1;
    const anchor = layout.anchorX + (side * layout.gap) / 2;
    arcs.push({
      ...dependency,
      direction,
      span,
      path: bracketPath(anchor, y1, y2, side, offset),
      head: arrowHead(anchor, y2, side),
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

// A filled arrowhead with its tip at (x, y), on the end of a bracket that
// comes back from the given side: pointing left for one on the right (+1),
// right for one on the left (-1).
export function arrowHead(x: number, y: number, side: -1 | 1): string {
  const base = x + side * HEAD_LENGTH;
  return `M ${x} ${y} L ${base} ${y - HEAD_HALF_WIDTH} L ${base} ${y + HEAD_HALF_WIDTH} Z`;
}
