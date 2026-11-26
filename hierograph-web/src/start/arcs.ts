// The geometry of the dependency arcs beside the hierarchy tree: bracket
// shapes between rows, on the right of the anchor column (away from the tree)
// for a dependency on a row below (with the tree's order), on the left, between
// the anchors and the labels, for one on a row above. The two sides start a
// gap apart, so the arcs of one side don't run into the other's. Each arc ends
// at the anchor of the row depended on, where its arrowhead goes.

export type ArcRow = { id: string; y: number };

export type ArcDependency = { from: string; to: string; weight: number };

export type Arc = ArcDependency & {
  direction: "down" | "up";
  // Ends on the row depended on.
  path: string;
  // The vertical distance, to draw long arcs first and short ones on top.
  span: number;
  // How far it reaches out from its anchor.
  offset: number;
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

// The innermost lane's offset, and the room between neighbouring lanes.
const MIN_OFFSET = 8;
const LANE_PITCH = 6;
const STUB = 4;
// How much further an arc down reaches back past its anchor at the end.
export const HEAD_ROOM = 6;
const MAX_CORNER = 8;

// Each side's arcs in lanes, evenly spaced: the shortest span innermost,
// each longer one a lane further out, so the brackets nest and no two share
// a vertical line. Lanes that don't fit within the side's reach move closer
// together instead of overlapping. With `groupOf`, each group of a side
// (e.g. what the selection uses and what uses it) gets lanes of its own, as
// if it were alone, and the smaller groups are centred on the largest's.
export function layoutArcs(
  rows: ArcRow[],
  dependencies: ArcDependency[],
  layout: ArcLayout,
  groupOf: (dependency: ArcDependency) => string = () => "",
): Arc[] {
  const ys = new Map(rows.map((row) => [row.id, row.y]));
  const placed = dependencies.flatMap((dependency) => {
    const y1 = ys.get(dependency.from);
    const y2 = ys.get(dependency.to);
    if (y1 === undefined || y2 === undefined || y1 === y2) return [];
    const direction: Arc["direction"] = y2 > y1 ? "down" : "up";
    return [{ dependency, y1, y2, direction, span: Math.abs(y2 - y1) }];
  });
  const arcs: Arc[] = [];
  const groups = [...new Set(placed.map((arc) => groupOf(arc.dependency)))];
  for (const direction of ["down", "up"] as const) {
    const max = direction === "down" ? layout.maxDown : layout.maxUp;
    const sign = direction === "down" ? 1 : -1;
    const anchor = layout.anchorX + (sign * layout.gap) / 2;
    // Each group's arcs in its own lanes, from the innermost out.
    const sets = groups.map((group) => {
      const side = placed
        .filter(
          (arc) =>
            arc.direction === direction && groupOf(arc.dependency) === group,
        )
        // Ties in a fixed order, so the lanes don't swap between renders.
        .sort(
          (a, b) =>
            a.span - b.span ||
            a.y1 - b.y1 ||
            a.dependency.from.localeCompare(b.dependency.from),
        );
      const pitch =
        side.length > 1
          ? Math.min(
              LANE_PITCH,
              Math.max(0, max - MIN_OFFSET) / (side.length - 1),
            )
          : 0;
      return { side, pitch, width: (side.length - 1) * pitch };
    });
    // The smaller sets centred on the largest one's lanes.
    const widest = Math.max(0, ...sets.map((set) => set.width));
    for (const { side, pitch, width } of sets) {
      const shift = (widest - width) / 2;
      side.forEach((arc, lane) => {
        const offset = Math.min(max, MIN_OFFSET + shift + lane * pitch);
        arcs.push({
          ...arc.dependency,
          direction,
          span: arc.span,
          offset,
          // An arc down ends a little further left than it starts: its
          // arrowhead clear of the corners at the anchor.
          path: bracketPath(
            anchor,
            arc.y1,
            arc.y2,
            sign,
            offset,
            direction === "down" ? HEAD_ROOM : 0,
          ),
        });
      });
    }
  }
  return arcs.sort((a, b) => b.span - a.span);
}

// From (x, y1) out by `offset` (to the left for side -1, the right for +1),
// a rounded corner, vertically to y2, a rounded corner, back to (x, y2), or
// `tail` past it.
export function bracketPath(
  x: number,
  y1: number,
  y2: number,
  side: -1 | 1,
  offset: number,
  tail = 0,
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
    `H ${x - side * tail}`,
  ].join(" ");
}

// How wide the arcs are, as laid out with the anchor column at 0: how far
// the arcs up reach to the left and the arcs down to the right; 0 for a side
// without arcs.
export function arcExtent(arcs: Arc[]): { up: number; down: number } {
  const reach = (direction: Arc["direction"]) =>
    Math.max(
      0,
      ...arcs
        .filter((arc) => arc.direction === direction)
        .map((arc) => arc.offset),
    );
  return { up: reach("up"), down: reach("down") };
}
