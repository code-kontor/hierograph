import type { MapModel } from "./mapModel";
import { isContainer } from "./structureLabels";

// Dependencies handed to the map from outside, e.g. by the agent in a link:
// `dependency_ids=4711-4802,4711-4815` (a source and its target, joined by
// `-`; a source with several targets may be grouped, `4711-4802.4815`). The
// map draws them between the boxes their ends lie in and, when the link sets
// no expansion, opens the map just far enough to tell their ends
// apart. Where a set comes from (the URL now, a named set later) is kept apart
// from how it is drawn: everything below works on the list of pairs.

// More pairs than this are cut: the URL reaches the server on a page load,
// whose limit for the request line and headers is 8 KB.
export const MAX_GIVEN = 200;

// At most this many containers are opened to show the given dependencies.
export const MAX_AUTO_EXPANDED = 60;

export type GivenPair = { sourceId: string; targetId: string };

export type ParsedGiven = {
  pairs: GivenPair[];
  // Entries that aren't `<id>-<id>(.<id>)*`, as given.
  malformed: string[];
  // How many pairs were left out over `MAX_GIVEN`.
  cut: number;
};

const ID = /^\d+$/;

// The pairs of the URL's entries, in their order, without duplicates.
export function parseGiven(
  entries: readonly string[] | undefined,
): ParsedGiven {
  const pairs: GivenPair[] = [];
  const malformed: string[] = [];
  const seen = new Set<string>();
  let cut = 0;
  for (const entry of entries ?? []) {
    const [sourceId, targets, ...rest] = entry.split("-");
    const targetIds = targets?.split(".") ?? [];
    if (
      rest.length > 0 ||
      !ID.test(sourceId) ||
      targetIds.length === 0 ||
      !targetIds.every((id) => ID.test(id))
    ) {
      malformed.push(entry);
      continue;
    }
    for (const targetId of targetIds) {
      const key = `${sourceId}-${targetId}`;
      if (seen.has(key)) continue;
      seen.add(key);
      if (pairs.length < MAX_GIVEN) pairs.push({ sourceId, targetId });
      else cut++;
    }
  }
  return { pairs, malformed, cut };
}

// One end of a given dependency as the server resolved it, in the current
// view: the node and its ancestors, nearest first, up to the root.
export type GivenEnd = {
  id: string;
  text: string;
  type: string;
  ancestors: { id: string; type: string; hasChildren: boolean }[];
};

// A pair with its ends (`null` when an id names no node) and its weight, the
// number of references from the source's subtree into the target's.
export type ResolvedPair = GivenPair & {
  source: GivenEnd | null;
  target: GivenEnd | null;
  weight: number;
};

// Why a given dependency isn't drawn: an id names no node; one end lies in
// the other; there is no dependency in the graph; or both ends lie in the
// same visible box (it opens to tell them apart).
export type NotDrawn = "unknown" | "nested" | "none" | "inside";

export type PlacedPair = ResolvedPair &
  (
    | { drawn: true; fromBox: string; toBox: string }
    | { drawn: false; reason: NotDrawn; box?: string }
  );

// An arrow for the given dependencies between two visible boxes: how many
// there are and their summed weight.
export type GivenArrow = {
  fromBox: string;
  toBox: string;
  count: number;
  weight: number;
};

export type GivenPlacement = {
  pairs: PlacedPair[];
  arrows: GivenArrow[];
  // The boxes the arrows join, and those both ends of a dependency lie in,
  // with the boxes around them.
  involved: Set<string>;
};

// Whether `end` is `other` or lies inside it.
function within(end: GivenEnd, other: GivenEnd): boolean {
  return end.id === other.id || end.ancestors.some((a) => a.id === other.id);
}

// The visible box an end lies in: itself, or its nearest ancestor on the map.
function boxOf(model: MapModel, end: GivenEnd): string | undefined {
  if (model.boxes.has(end.id)) return end.id;
  return end.ancestors.find((ancestor) => model.boxes.has(ancestor.id))?.id;
}

// Where each given dependency is drawn on the map as it is now, and the
// arrows: the dependencies between the same two boxes become one.
export function placeGiven(
  model: MapModel,
  resolved: readonly ResolvedPair[],
): GivenPlacement {
  const arrows = new Map<string, GivenArrow>();
  const involved = new Set<string>();
  const involve = (box: string) => {
    for (let at: string | null = box; at !== null;) {
      involved.add(at);
      at = model.boxes.get(at)?.parentId ?? null;
    }
  };
  const pairs = resolved.map((pair): PlacedPair => {
    const { source, target } = pair;
    if (!source || !target) return { ...pair, drawn: false, reason: "unknown" };
    if (within(source, target) || within(target, source)) {
      return { ...pair, drawn: false, reason: "nested" };
    }
    if (pair.weight === 0) return { ...pair, drawn: false, reason: "none" };
    const fromBox = boxOf(model, source);
    const toBox = boxOf(model, target);
    if (fromBox === undefined || toBox === undefined || fromBox === toBox) {
      if (fromBox !== undefined) involve(fromBox);
      return { ...pair, drawn: false, reason: "inside", box: fromBox };
    }
    const key = `${fromBox}>${toBox}`;
    const arrow = arrows.get(key) ?? { fromBox, toBox, count: 0, weight: 0 };
    arrow.count += 1;
    arrow.weight += pair.weight;
    arrows.set(key, arrow);
    involve(fromBox);
    involve(toBox);
    return { ...pair, drawn: true, fromBox, toBox };
  });
  return { pairs, arrows: [...arrows.values()], involved };
}

// The containers to open so that the two ends of every given dependency lie
// in different boxes: for each pair, the containers from the top down to the
// innermost one holding both ends, not further. Shallow containers first, at
// most `limit`; a pair whose ends share a type (two members) stays as it is.
export function expansionFor(
  resolved: readonly ResolvedPair[],
  limit: number = MAX_AUTO_EXPANDED,
): string[] {
  const depthOf = new Map<string, number>();
  for (const { source, target, weight } of resolved) {
    if (!source || !target || weight === 0) continue;
    if (within(source, target) || within(target, source)) continue;
    // Top down, the root first.
    const down = (end: GivenEnd) => [...end.ancestors].reverse();
    const fromTop = down(source);
    const toTop = down(target);
    let shared = 0;
    while (
      shared < fromTop.length &&
      shared < toTop.length &&
      fromTop[shared].id === toTop[shared].id
    ) {
      shared++;
    }
    // fromTop[shared - 1] holds both ends; the root (index 0) is always open.
    const holder = fromTop[shared - 1];
    if (!holder || !isContainer(holder.type, holder.hasChildren)) continue;
    for (let depth = 1; depth < shared; depth++) {
      const id = fromTop[depth].id;
      if (!depthOf.has(id)) depthOf.set(id, depth);
    }
  }
  return [...depthOf.entries()]
    .sort((a, b) => a[1] - b[1])
    .slice(0, limit)
    .map(([id]) => id);
}
