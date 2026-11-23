import type { DependencyRole } from "@/design-system/graph/DependencyArrow";

// The arrows' layout: near-straight lines from box to box, drawn over the
// boxes, between the edges that face each other. An arrow to a box below
// leaves its source at the bottom and enters its target at the top; one to a
// box above leaves at the top and enters at the bottom; one to a box beside
// it, in the same row, arches from top to top. The ends on one edge cluster
// around the point facing the other box, kept apart and ordered by the
// direction they leave in, so the lines neither overlap nor cross at the box,
// and a last repair swaps neighbouring ends whose lines still cross.

export type Rect = { l: number; t: number; r: number; b: number };

// "feedback": against the levels (dashed, in the cycle colour); "uses" and
// "usedBy": what the selection depends on and what depends on it.
export type ArrowRole = DependencyRole;

export type ArrowSpec = {
  key: string;
  fromId: string;
  toId: string;
  from: Rect;
  to: Rect;
  role: ArrowRole;
  weight: number;
  width: number;
  title: string;
};

export type Arrow = {
  key: string;
  fromId: string;
  toId: string;
  d: string;
  role: ArrowRole;
  weight: number;
  width: number;
  title: string;
};

type Point = { x: number; y: number };
type Kind = "down" | "up" | "level";
type End = { arrow: number; end: 0 | 1 };

// How far the ends stay from a box's left and right corners.
const INSET = 24;
// The least distance between two ends on one edge.
const SPACING = 18;
// Where in two boxes' overlap their lines meet: right of the middle, where
// the boxes' centred names aren't.
const BESIDE_NAMES = 0.7;
// How far inside its boxes an arrow starts and ends, so the gaps between the
// boxes can stay narrow.
const INSIDE = 10;
// How much a line's end leans sideways, per unit of its sideways travel
// over its height.
const LEAN = 0.6;
// The most a line's end leans sideways against its vertical part: 0.9 keeps
// the arrowhead steeper than 45 degrees.
const STEEPEST = 0.9;
// The straight piece the arrowhead sits on.
const HEAD = 10;
// How far toward its end a line already leans as it leaves its box: lines
// that bent late (straight out, then sideways) crossed their shorter
// neighbours from the same edge, which bent earlier.
const START_LEAN = 0.3;

function kindOf(from: Rect, to: Rect): Kind {
  if (to.t >= from.b - 2) return "down";
  if (to.b <= from.t + 2) return "up";
  return "level";
}

// The edges an arrow of this kind leaves and enters: true for a bottom edge.
function edgesOf(kind: Kind): [boolean, boolean] {
  if (kind === "down") return [true, false];
  if (kind === "up") return [false, true];
  return [false, false];
}

// How far ends stay from a box's left and right corners: INSET, or a
// quarter of a narrow box's width.
function inset(rect: Rect): number {
  return Math.min(INSET, (rect.r - rect.l) / 4);
}

// Where on an edge of `own` an end would sit: facing `other` (in the two
// boxes' overlap, right of its middle, so the lines pass beside the centred
// names rather than through them; or toward the other's centre), off the
// corners.
function facing(own: Rect, other: Rect): number {
  const lo = Math.max(own.l, other.l);
  const hi = Math.min(own.r, other.r);
  const target =
    hi - lo > 20 ? lo + (hi - lo) * BESIDE_NAMES : (other.l + other.r) / 2;
  return Math.max(own.l + inset(own), Math.min(own.r - inset(own), target));
}

// Places ascending wanted positions at least SPACING apart within [lo, hi]:
// separated outwards, then the cluster moved back so it centres where the
// wanted positions do.
function spread(wanted: number[], lo: number, hi: number): number[] {
  const n = wanted.length;
  if (n === 0) return [];
  const gap = n > 1 ? Math.min(SPACING, (hi - lo) / (n - 1)) : 0;
  const out = [...wanted];
  for (let i = 1; i < n; i++) out[i] = Math.max(out[i], out[i - 1] + gap);
  const mean = (values: number[]) =>
    values.reduce((sum, v) => sum + v, 0) / values.length;
  const shift = Math.max(
    lo - out[0],
    Math.min(hi - out[n - 1], mean(wanted) - mean(out)),
  );
  return out.map((v) => v + shift);
}

export function layoutArrows(specs: ArrowSpec[]): {
  arrows: Arrow[];
  gutter: number;
  gutterLeft: number;
} {
  const kinds = specs.map((spec) => kindOf(spec.from, spec.to));

  // The ends, by the edge they sit on.
  const edges = new Map<string, { rect: Rect; ends: End[] }>();
  specs.forEach((spec, index) => {
    const [fromBottom, toBottom] = edgesOf(kinds[index]);
    const sides: [string, Rect, boolean, 0 | 1][] = [
      [spec.fromId, spec.from, fromBottom, 0],
      [spec.toId, spec.to, toBottom, 1],
    ];
    for (const [id, rect, bottom, end] of sides) {
      const key = `${id}:${bottom ? "bottom" : "top"}`;
      const entry = edges.get(key) ?? { rect, ends: [] };
      entry.ends.push({ arrow: index, end });
      edges.set(key, entry);
    }
  });

  // Each end facing the other box at first; then, a few times, each edge's
  // ends ordered by the direction toward where their other ends now sit
  // (sideways distance per unit of height), wanting to sit right above or
  // below them, and clustered there, kept apart: both ends of every line come
  // to agree, so the lines run nearly straight and don't cross.
  const points: Point[][] = specs.map((spec, index) => {
    const [fromBottom, toBottom] = edgesOf(kinds[index]);
    return [
      {
        x: facing(spec.from, spec.to),
        y: fromBottom ? spec.from.b : spec.from.t,
      },
      { x: facing(spec.to, spec.from), y: toBottom ? spec.to.b : spec.to.t },
    ];
  });
  for (let pass = 0; pass < 4; pass++) {
    const next = points.map(([a, b]) => [{ ...a }, { ...b }]);
    for (const { rect, ends } of edges.values()) {
      const middle = (rect.l + rect.r) / 2;
      const y = points[ends[0].arrow][ends[0].end].y;
      const direction = (e: End) => {
        const other = points[e.arrow][1 - e.end];
        return (other.x - middle) / Math.max(1, Math.abs(other.y - y));
      };
      // In the order of where the other ends sit, left to right, so the
      // starts on an edge read in the same order as their ends (and the ends
      // as their starts); the direction only breaks ties. Where that order
      // makes two lines cross, the repair below swaps those two.
      const sorted = [...ends].sort(
        (a, b) =>
          points[a.arrow][1 - a.end].x - points[b.arrow][1 - b.end].x ||
          direction(a) - direction(b) ||
          a.arrow - b.arrow,
      );
      const wanted = sorted.map((e) =>
        Math.max(
          rect.l + inset(rect),
          Math.min(rect.r - inset(rect), points[e.arrow][1 - e.end].x),
        ),
      );
      // In that order: each wanted place at least its predecessor's.
      for (let i = 1; i < wanted.length; i++) {
        wanted[i] = Math.max(wanted[i], wanted[i - 1]);
      }
      const placed = spread(wanted, rect.l + inset(rect), rect.r - inset(rect));
      sorted.forEach((e, i) => {
        next[e.arrow][e.end] = { x: placed[i], y };
      });
    }
    next.forEach((pair, index) => {
      points[index] = pair;
    });
  }

  untangle(kinds, points, edges);

  const arrows = specs.map((spec, index): Arrow => {
    const kind = kinds[index];
    const [fromBottom, toBottom] = edgesOf(kind);
    // Inside the edges, into the box at each end.
    const p = {
      x: points[index][0].x,
      y: points[index][0].y + (fromBottom ? -INSIDE : INSIDE),
    };
    const q = {
      x: points[index][1].x,
      y: points[index][1].y + (toBottom ? -INSIDE : INSIDE),
    };
    return {
      key: spec.key,
      fromId: spec.fromId,
      toId: spec.toId,
      d: pathOf(kind, p, q),
      role: spec.role,
      weight: spec.weight,
      width: spec.width,
      title: spec.title,
    };
  });
  return { arrows, gutter: 0, gutterLeft: 0 };
}

// The line's shape: a cubic curve from its start to the foot of its head,
// then a straight piece as long as the head. The curve leaves straight out of
// its box; the straight piece comes in leaning the way the line travels, but
// never flatter than about 48 degrees, so the arrowhead on it follows the arc
// and stays steep even on a line that travels far sideways over little
// height. A line between boxes in the same row arches over them.
function shapeOf(
  kind: Kind,
  p: Point,
  q: Point,
): { c1: Point; c2: Point; foot: Point } {
  const level = kind === "level";
  // Which way the line runs at its end: down into a top edge, or up into a
  // bottom edge.
  const sign = level || q.y > p.y ? 1 : -1;
  const top = Math.min(p.y, q.y) - 18 - Math.abs(q.x - p.x) * 0.12;
  const height = level ? q.y - top : Math.abs(q.y - p.y);
  // The end direction (lean, sign): sideways per unit of height, capped.
  const lean = Math.max(
    -STEEPEST,
    Math.min(STEEPEST, ((q.x - p.x) * LEAN) / Math.max(1, height)),
  );
  const length = Math.hypot(lean, 1);
  const u = { x: lean / length, y: sign / length };
  const head = Math.min(HEAD, height / 2);
  const foot = { x: q.x - u.x * head, y: q.y - u.y * head };
  if (level) {
    // Back along the end direction up to the arch's top.
    const back = (foot.y - top) / u.y;
    return {
      c1: { x: p.x, y: top },
      c2: { x: foot.x - u.x * back, y: top },
      foot,
    };
  }
  const reach = Math.abs(foot.y - p.y) * 0.5;
  return {
    c1: { x: p.x + (foot.x - p.x) * START_LEAN, y: p.y + sign * reach },
    c2: { x: foot.x - (u.x / Math.abs(u.y)) * reach, y: foot.y - sign * reach },
    foot,
  };
}

function pathOf(kind: Kind, p: Point, q: Point): string {
  const { c1, c2, foot } = shapeOf(kind, p, q);
  return (
    `M${p.x},${p.y} C${c1.x},${c1.y} ${c2.x},${c2.y} ${foot.x},${foot.y}` +
    ` L${q.x},${q.y}`
  );
}

// A line as points along it, for the crossing test.
function linePoints(kind: Kind, p: Point, q: Point, steps = 24): Point[] {
  const { c1, c2, foot } = shapeOf(kind, p, q);
  const out: Point[] = [];
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const u = 1 - t;
    const w = [u * u * u, 3 * u * u * t, 3 * u * t * t, t * t * t];
    out.push({
      x: w[0] * p.x + w[1] * c1.x + w[2] * c2.x + w[3] * foot.x,
      y: w[0] * p.y + w[1] * c1.y + w[2] * c2.y + w[3] * foot.y,
    });
  }
  out.push(q);
  return out;
}

function segmentsCross(a: Point, b: Point, c: Point, d: Point): boolean {
  const side = (p: Point, q: Point, r: Point) =>
    (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x);
  return side(a, b, c) * side(a, b, d) < 0 && side(c, d, a) * side(c, d, b) < 0;
}

// The segment tests the repair may spend: with many arrows on one edge it
// stops there, keeping the fewest crossings found so far. Counted, not timed,
// so the same map always gets the same arrows.
const WORK_BUDGET = 4_000_000;

function linesCross(
  first: Line,
  second: Line,
  work: { done: number },
): boolean {
  // Where the two can cross at all: the overlap of their bounding boxes.
  const l = Math.max(first.box.l, second.box.l);
  const r = Math.min(first.box.r, second.box.r);
  const t = Math.max(first.box.t, second.box.t);
  const b = Math.min(first.box.b, second.box.b);
  if (l > r || t > b) return false;
  const within = (points: Point[]) => {
    const found: number[] = [];
    for (let i = 0; i < points.length - 1; i++) {
      const p = points[i];
      const q = points[i + 1];
      if (Math.max(p.x, q.x) < l || Math.min(p.x, q.x) > r) continue;
      if (Math.max(p.y, q.y) < t || Math.min(p.y, q.y) > b) continue;
      found.push(i);
    }
    return found;
  };
  const a = first.points;
  const c = second.points;
  const ofFirst = within(a);
  if (ofFirst.length === 0) return false;
  const ofSecond = within(c);
  for (const i of ofFirst) {
    for (const j of ofSecond) {
      work.done++;
      if (segmentsCross(a[i], a[i + 1], c[j], c[j + 1])) return true;
    }
  }
  return false;
}

// A line's points, with their bounding box for a quick test first.
type Line = { points: Point[]; box: Rect };

function lineOf(kind: Kind, p: Point, q: Point): Line {
  const points = linePoints(kind, p, q);
  const xs = points.map((point) => point.x);
  const ys = points.map((point) => point.y);
  return {
    points,
    box: {
      l: Math.min(...xs),
      r: Math.max(...xs),
      t: Math.min(...ys),
      b: Math.max(...ys),
    },
  };
}

// The last repair: where two ends on an edge belong to lines that cross
// anything, their places are swapped, and the swap is kept when it leaves
// fewer crossings; any two ends, not only neighbours, so a line can move
// past several others. Each line's crossings are kept and only the two
// swapped lines are tested again.
function untangle(
  kinds: Kind[],
  points: Point[][],
  edges: Map<string, { ends: End[] }>,
) {
  const n = kinds.length;
  if (n < 2) return;
  const work = { done: 0 };
  const lines = kinds.map((kind, index) =>
    lineOf(kind, points[index][0], points[index][1]),
  );
  const crossed = (index: number) => {
    const found = new Set<number>();
    for (let other = 0; other < n; other++) {
      if (other !== index && linesCross(lines[index], lines[other], work)) {
        found.add(other);
      }
    }
    return found;
  };
  const crossings: Set<number>[] = lines.map(() => new Set());
  for (let i = 0; i < n; i++) {
    for (let k = i + 1; k < n; k++) {
      if (linesCross(lines[i], lines[k], work)) {
        crossings[i].add(k);
        crossings[k].add(i);
      }
    }
  }
  const replace = (index: number, next: Set<number>) => {
    for (const other of crossings[index]) crossings[other].delete(index);
    for (const other of next) crossings[other].add(index);
    crossings[index] = next;
  };
  for (let round = 0; round < 6; round++) {
    let improved = false;
    for (const { ends } of edges.values()) {
      for (let i = 0; i < ends.length; i++) {
        for (let k = i + 1; k < ends.length; k++) {
          if (work.done > WORK_BUDGET) return;
          const a = ends[i];
          const b = ends[k];
          if (a.arrow === b.arrow) continue;
          const before = crossings[a.arrow].size + crossings[b.arrow].size;
          if (before === 0) continue;
          const swap = () => {
            const x = points[a.arrow][a.end].x;
            points[a.arrow][a.end].x = points[b.arrow][b.end].x;
            points[b.arrow][b.end].x = x;
          };
          const lineA = lines[a.arrow];
          const lineB = lines[b.arrow];
          swap();
          lines[a.arrow] = lineOf(
            kinds[a.arrow],
            points[a.arrow][0],
            points[a.arrow][1],
          );
          lines[b.arrow] = lineOf(
            kinds[b.arrow],
            points[b.arrow][0],
            points[b.arrow][1],
          );
          const nextA = crossed(a.arrow);
          const nextB = crossed(b.arrow);
          if (nextA.size + nextB.size < before) {
            replace(a.arrow, nextA);
            replace(b.arrow, nextB);
            improved = true;
          } else {
            swap();
            lines[a.arrow] = lineA;
            lines[b.arrow] = lineB;
          }
        }
      }
    }
    if (!improved) return;
  }
}
