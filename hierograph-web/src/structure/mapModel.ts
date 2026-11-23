import type { StructureLevel, StructureNode } from "./queries";
import { isContainer, structureLabel } from "./structureLabels";

// One box of the map: a node shown as a box, its container and where it sits.
export type MapBox = StructureNode & {
  parentId: string | null;
  label: string;
  depth: number;
  // External Types and everything in it: library code, drawn apart.
  external: boolean;
  // Its row in its container, 0 at the bottom.
  level: number;
  inTangle: boolean;
  container: boolean;
};

// An entry of a row: a box, or a tangle with its own rows (top first).
export type MapItem =
  { kind: "box"; id: string } | { kind: "tangle"; rows: string[][] };

export type FeedbackDependency = {
  containerId: string;
  sourceId: string;
  targetId: string;
  weight: number;
};

// The visible map: every box, the rows of each open container (top first),
// the boxes whose content isn't shown (the frontier: collapsed containers and
// leaves), and the feedback dependencies of the open containers, heaviest
// first.
export type MapModel = {
  rootId: string;
  boxes: Map<string, MapBox>;
  rowsOf: Map<string, MapItem[][]>;
  frontier: string[];
  feedback: FeedbackDependency[];
  // The share of its row a box takes: how many boxes are visible in it, as
  // the widest of its rows has them (1 for a box whose content isn't shown).
  weights: Map<string, number>;
  // The packages whose rows are needed to fold a module's package chains,
  // and not loaded yet; the module opens once they are.
  pending: string[];
};

// The map from the root's and the expanded containers' levelizations. A
// container is open when it is expanded and its rows are loaded; the root is
// always open, and not drawn as a box. In a module, a package that holds
// nothing but one package, and so on down, is folded into one box: the last
// package of the chain, named in full (`io` > `hierograph` > `server` is
// `io.hierograph.server`).
export function buildModel(
  rootId: string,
  levels: Map<string, StructureLevel>,
  expanded: ReadonlySet<string>,
): MapModel {
  const boxes = new Map<string, MapBox>();
  const rowsOf = new Map<string, MapItem[][]>();
  const frontier: string[] = [];
  const feedback: FeedbackDependency[] = [];
  const pending: string[] = [];

  // The package a chain of lone packages from `node` ends in, or null while a
  // package's rows on the way are still loading.
  const chainEnd = (node: StructureNode): StructureNode | null => {
    let at = node;
    for (;;) {
      if (!at.hasChildren) return at;
      const level = levels.get(at.id);
      if (!level) {
        pending.push(at.id);
        return null;
      }
      const children = level.levels.flat();
      if (children.length !== 1 || children[0].type !== "java.package") {
        return at;
      }
      at = children[0];
    }
  };

  // A module's rows with each package chain folded into its last package;
  // null while a chain is still loading.
  const folded = (level: StructureLevel): StructureLevel | null => {
    const ends = new Map<string, StructureNode>();
    let complete = true;
    for (const node of level.levels.flat()) {
      if (node.type !== "java.package") continue;
      const end = chainEnd(node);
      if (end === null) complete = false;
      else if (end.id !== node.id) ends.set(node.id, end);
    }
    if (!complete) return null;
    if (ends.size === 0) return level;
    const idOf = (id: string) => ends.get(id)?.id ?? id;
    return {
      levels: level.levels.map((nodes) =>
        nodes.map((node) => ends.get(node.id) ?? node),
      ),
      tangles: level.tangles.map((tangle) => ({
        level: tangle.level,
        nodeIds: tangle.nodeIds.map(idOf),
        rows: tangle.rows.map((row) => row.map(idOf)),
      })),
      feedback: level.feedback.map((dep) => ({
        ...dep,
        sourceId: idOf(dep.sourceId),
        targetId: idOf(dep.targetId),
      })),
    };
  };

  // Opens a container: lays its rows out and opens its expanded children.
  // False while its rows, or a module's chains, are still loading.
  const open = (
    id: string,
    parentText: string,
    depth: number,
    external: boolean,
  ) => {
    const loaded = levels.get(id);
    if (!loaded) return false;
    const level =
      boxes.get(id)?.type === "java.module" ? folded(loaded) : loaded;
    if (!level) return false;
    const tangleOf = new Map<string, StructureLevel["tangles"][number]>();
    for (const tangle of level.tangles) {
      for (const member of tangle.nodeIds) tangleOf.set(member, tangle);
    }
    const rows: MapItem[][] = [];
    level.levels.forEach((nodes, index) => {
      const row: MapItem[] = [];
      for (const node of nodes) {
        const tangle = tangleOf.get(node.id);
        const box: MapBox = {
          ...node,
          parentId: id === rootId ? null : id,
          label: structureLabel(node.type, node.text, parentText),
          depth,
          external: external || node.text === "External Types",
          level: index,
          inTangle: tangle !== undefined,
          container: isContainer(node.type, node.hasChildren),
        };
        boxes.set(node.id, box);
        if (!tangle) row.push({ kind: "box", id: node.id });
        else if (tangle.nodeIds[0] === node.id) {
          row.push({ kind: "tangle", rows: [...tangle.rows].reverse() });
        }
      }
      rows.push(row);
    });
    rowsOf.set(id, rows.reverse());
    for (const dep of level.feedback)
      feedback.push({ containerId: id, ...dep });
    for (const nodes of level.levels) {
      for (const node of nodes) {
        const box = boxes.get(node.id)!;
        const opened =
          box.container &&
          expanded.has(node.id) &&
          open(node.id, node.text, depth + 1, box.external);
        if (!opened) frontier.push(node.id);
      }
    }
    return true;
  };
  open(rootId, "", 0, false);
  feedback.sort((a, b) => b.weight - a.weight);
  const weights = weightsOf(boxes.keys(), rowsOf);
  return { rootId, boxes, rowsOf, frontier, feedback, weights, pending };
}

// Each box's weight: how many boxes are visible in it.
function weightsOf(
  ids: Iterable<string>,
  rowsOf: Map<string, MapItem[][]>,
): Map<string, number> {
  const weights = new Map<string, number>();
  const weightOf = (id: string): number => {
    const known = weights.get(id);
    if (known !== undefined) return known;
    const rows = rowsOf.get(id);
    const weight = rows ? rowsWeight(rows, weightOf) : 1;
    weights.set(id, weight);
    return weight;
  };
  for (const id of ids) weightOf(id);
  return weights;
}

// The map with only the boxes in `keep` drawn: the others left out of their
// rows, rows and tangles left empty dropped, and the weights counted again,
// so what is kept lays out compactly. The root is always kept.
export function restrictModel(
  model: MapModel,
  keep: ReadonlySet<string>,
): MapModel {
  const rowsOf = new Map<string, MapItem[][]>();
  for (const [id, rows] of model.rowsOf) {
    if (id !== model.rootId && !keep.has(id)) continue;
    const kept = rows
      .map((row) =>
        row.flatMap((item): MapItem[] => {
          if (item.kind === "box") return keep.has(item.id) ? [item] : [];
          const tangleRows = item.rows
            .map((ids) => ids.filter((member) => keep.has(member)))
            .filter((ids) => ids.length > 0);
          return tangleRows.length > 0
            ? [{ kind: "tangle", rows: tangleRows }]
            : [];
        }),
      )
      .filter((row) => row.length > 0);
    rowsOf.set(id, kept);
  }
  const boxes = new Map([...model.boxes].filter(([id]) => keep.has(id)));
  return {
    ...model,
    boxes,
    rowsOf,
    frontier: model.frontier.filter((id) => keep.has(id)),
    feedback: model.feedback.filter(
      (dep) => keep.has(dep.sourceId) && keep.has(dep.targetId),
    ),
    weights: weightsOf(boxes.keys(), rowsOf),
  };
}

// The weight of a set of rows: the widest row's, a row's the sum of its
// entries'.
function rowsWeight(
  rows: MapItem[][],
  weightOf: (id: string) => number,
): number {
  return Math.max(
    1,
    ...rows.map((row) =>
      row.reduce((sum, item) => sum + itemWeight(item, weightOf), 0),
    ),
  );
}

export function itemWeight(
  item: MapItem,
  weightOf: (id: string) => number,
): number {
  if (item.kind === "box") return weightOf(item.id);
  return Math.max(
    1,
    ...item.rows.map((ids) => ids.reduce((sum, id) => sum + weightOf(id), 0)),
  );
}

// Whether `id` is `ancestor` or lies inside it.
export function isWithin(
  model: MapModel,
  id: string,
  ancestor: string,
): boolean {
  for (
    let k: string | null = id;
    k !== null;
    k = model.boxes.get(k)?.parentId ?? null
  ) {
    if (k === ancestor) return true;
  }
  return false;
}

// A dependency between two visible boxes, one inside the selection and one
// outside it: what the arrows are drawn from.
export type TracedLink = {
  from: string;
  to: string;
  weight: number;
  role: "uses" | "usedBy";
};

// What the selected box depends on and what depends on it, among the visible
// boxes, with the summed weights: from the matrix over the frontier, counting
// only dependencies that leave or enter the selection. The links keep the
// visible box inside the selection each one starts or ends at, so an
// expanded selection's arrows come from its contents.
export function trace(
  model: MapModel,
  selected: string,
  cells: { from: string; to: string; weight: number }[],
): {
  uses: Map<string, number>;
  usedBy: Map<string, number>;
  links: TracedLink[];
} {
  const uses = new Map<string, number>();
  const usedBy = new Map<string, number>();
  const links: TracedLink[] = [];
  for (const { from, to, weight } of cells) {
    const fromInside = isWithin(model, from, selected);
    const toInside = isWithin(model, to, selected);
    if (fromInside && !toInside) {
      uses.set(to, (uses.get(to) ?? 0) + weight);
      links.push({ from, to, weight, role: "uses" });
    }
    if (toInside && !fromInside) {
      usedBy.set(from, (usedBy.get(from) ?? 0) + weight);
      links.push({ from, to, weight, role: "usedBy" });
    }
  }
  return { uses, usedBy, links };
}
