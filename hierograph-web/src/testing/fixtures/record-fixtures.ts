// Records the GraphQL fixtures the browser tests run against, from a running
// Hierograph server serving the fixture-app (see README.md in this directory).
//
//   pnpm fixtures:record                      the default scenario: a loaded graph
//   pnpm fixtures:record --scenario no-graph  a server without a graph
//
// Writes `<Operation>.json` (default scenario) or `<Operation>.<scenario>.json`
// next to this script. Responses are normalised (normalize.ts), so re-recording
// unchanged data yields identical files.

import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { parseArgs } from "node:util";

import { GraphQLClient } from "graphql-request";

import {
  DependencyPickPathDocument,
  DependencySetChildrenDocument,
  DependencySetDocument,
  DependencySetPageDocument,
  GraphStatusDocument,
  HierarchyArcsDocument,
  HierarchyTreeChildrenDocument,
  HierarchyTreeRootDocument,
  HierarchyViewsDocument,
  ServerStatusDocument,
  StructureCellsDocument,
  StructureGivenDocument,
  StructureLevelDocument,
  StructureRootDocument,
  ViewExclusionsDocument,
} from "../../graphql/generated/graphql.ts";
import { normalize } from "./normalize.ts";

const GRAPHQL_URL =
  process.env.HIEROGRAPH_GRAPHQL_URL ?? "http://localhost:8765/graphql";
const FIXTURES_DIR = import.meta.dirname;

// The operations without variables, recorded in every scenario. A new
// operation the tests need is added here, or below like the tree queries when
// it takes variables (and a handler in ../msw/handlers.ts).
const OPERATIONS = [
  { name: "GraphStatus", document: GraphStatusDocument },
  { name: "HierarchyViews", document: HierarchyViewsDocument },
  { name: "ServerStatus", document: ServerStatusDocument },
  { name: "ViewExclusions", document: ViewExclusionsDocument },
] as const;

const { values } = parseArgs({
  options: { scenario: { type: "string", default: "default" } },
});
const scenario = values.scenario;
const client = new GraphQLClient(GRAPHQL_URL);

type Entry = { variables: Record<string, unknown>; data: unknown };

function write(name: string, entries: Entry[]) {
  const file = resolve(
    FIXTURES_DIR,
    scenario === "default" ? `${name}.json` : `${name}.${scenario}.json`,
  );
  const fixture = { operation: name, scenario, entries };
  writeFileSync(file, `${JSON.stringify(fixture, null, 2)}\n`);
  console.log(`Recorded ${name} (${scenario}) → ${file}`);
}

for (const operation of OPERATIONS) {
  const data = await client.request<Record<string, unknown>>(
    operation.document,
  );
  write(operation.name, [{ variables: {}, data: normalize(data as never) }]);
}

// The hierarchy tree, only with a graph: the complete tree (the fixture-app is
// small), in the scanned hierarchy, in the `layers` view, whose groups sit
// deep inside the module, and in the `locations` view, which excludes a tenant. Each level is asked for as the screen asks for it:
// in dependency order, a type's members in source order (`ordersChildren`).
type TreeNode = {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
};
type TreeChildren = {
  nodes?: TreeNode[];
  orderedAdjacencyMatrix?: { orderedNodes: TreeNode[] };
};

// The screen's rules (start/treeLabels.ts), repeated here: the recorder may
// not import a screen (dependency-cruiser's folder cycles). Should they drift
// apart, the tests fail on a request without a recorded answer.
const TYPE_KINDS = new Set([
  "java.class",
  "java.interface",
  "java.enum",
  "java.record",
  "java.annotation",
  "external.type",
]);
// A type's children are its members, which keep source order.
const ordersChildren = (node: TreeNode) => !TYPE_KINDS.has(node.type);

const childNodes = (children: TreeChildren) =>
  children.orderedAdjacencyMatrix?.orderedNodes ?? children.nodes ?? [];

if (scenario === "default") {
  const roots: Entry[] = [];
  const children: Entry[] = [];
  const expand = async (view: string | null, nodes: TreeNode[]) => {
    for (const node of nodes) {
      if (!node.hasChildren) continue;
      const variables = {
        view,
        id: node.id,
        ordered: ordersChildren(node),
      };
      const data = await client.request<{
        hierarchicalGraph: { node: { children: TreeChildren } };
      }>(HierarchyTreeChildrenDocument, variables);
      children.push({ variables, data: normalize(data as never) });
      await expand(view, childNodes(data.hierarchicalGraph.node.children));
    }
  };
  for (const view of [null, "layers", "locations"]) {
    const variables = { view };
    const data = await client.request<{
      hierarchicalGraph: { rootNode: { children: TreeChildren } };
    }>(HierarchyTreeRootDocument, variables);
    roots.push({ variables, data: normalize(data as never) });
    await expand(view, childNodes(data.hierarchicalGraph.rootNode.children));
  }
  write("HierarchyTreeRoot", roots);
  write("HierarchyTreeChildren", children);

  // The arcs between the visible rows, for every state the tests pass
  // through: from a collapsed tree, expanding the nodes along each path one by
  // one. The visible rows are computed as the screen marks them (`frontier` in
  // HierarchyTree.tsx): rows whose children aren't shown.
  const levels = new Map<string, TreeNode[]>();
  const levelKey = (view: string | null, id: string) => `${view}|${id}`;
  for (const entry of roots) {
    const view = entry.variables.view as string | null;
    const data = entry.data as {
      hierarchicalGraph: { rootNode: { children: TreeChildren } };
    };
    levels.set(
      levelKey(view, "root"),
      childNodes(data.hierarchicalGraph.rootNode.children),
    );
  }
  for (const entry of children) {
    const { view, id } = entry.variables as { view: string | null; id: string };
    const data = entry.data as {
      hierarchicalGraph: { node: { children: TreeChildren } };
    };
    levels.set(
      levelKey(view, id),
      childNodes(data.hierarchicalGraph.node.children),
    );
  }
  const frontier = (view: string | null, expanded: Set<string>) => {
    const ids: string[] = [];
    const visit = (nodes: TreeNode[]) => {
      for (const node of nodes) {
        if (expanded.has(node.id) && node.hasChildren) {
          visit(levels.get(levelKey(view, node.id)) ?? []);
        } else {
          ids.push(node.id);
        }
      }
    };
    visit(levels.get(levelKey(view, "root")) ?? []);
    return ids.sort();
  };
  // The paths, by the nodes' qualified names (a view group's is `<view>:<name>`).
  // The tree is folded (#63): `org` › `org.hg` hold nothing but each other,
  // so the module's one row is `org.hg.fixture`.
  const BASIC = [
    "io.hierograph.examples:fixture-app:1.0.0",
    "org.hg.fixture",
    "org.hg.fixture.basic",
  ];
  const PATHS: { view: string | null; path: string[] }[] = [
    {
      view: null,
      path: [
        ...BASIC,
        "org.hg.fixture.basic.core",
        "org.hg.fixture.basic.core.PlainClass",
      ],
    },
    { view: null, path: [...BASIC, "org.hg.fixture.basic.cycle"] },
    { view: null, path: [...BASIC.slice(0, 2), "org.hg.fixture.locations"] },
    // To a package with a folded chain inside: `batch` holds only `nightly`.
    {
      view: null,
      path: [
        ...BASIC.slice(0, 2),
        "org.hg.fixture.locations",
        "org.hg.fixture.locations.app",
      ],
    },
    { view: null, path: ["External Types", "java"] },
    {
      view: "layers",
      path: [
        ...BASIC.slice(0, 2),
        "org.hg.fixture.locations",
        "layers:application",
      ],
    },
    // Without the excluded `basic` tenant, `org.hg.fixture` holds nothing but
    // `locations`, so the chain folds one package further.
    { view: "locations", path: [BASIC[0], "org.hg.fixture.locations"] },
  ];
  const arcs: Entry[] = [];
  const recorded = new Set<string>();
  const record = async (view: string | null, expanded: Set<string>) => {
    const ids = frontier(view, expanded);
    const key = `${view}|${ids.join(",")}`;
    if (ids.length < 2 || recorded.has(key)) return;
    recorded.add(key);
    const variables = { view, ids };
    const data = await client.request(HierarchyArcsDocument, variables);
    arcs.push({ variables, data: normalize(data as never) });
  };
  for (const { view, path } of PATHS) {
    const expanded = new Set<string>();
    // The collapsed tree has rows with arcs already: the modules and External
    // Types.
    await record(view, expanded);
    let level = levels.get(levelKey(view, "root")) ?? [];
    for (const text of path) {
      const node = level.find((candidate) => candidate.text === text);
      if (!node)
        throw new Error(`No "${text}" in the tree (${path.join(" › ")})`);
      expanded.add(node.id);
      level = levels.get(levelKey(view, node.id)) ?? [];
      await record(view, expanded);
    }
  }
  write("HierarchyArcs", arcs);

  // The dependency pane of a selection (#77, #79), in the scanned hierarchy,
  // as the screen asks for it (start/DependencyTrees.tsx,
  // start/DependencyList.tsx): its two sets; the three trees, every level that
  // can be opened (not below a type): "Used by" (the incoming dependencies'
  // sources), "Selection" (its parts with an incoming or an outgoing
  // dependency) and "Uses" (the outgoing dependencies' targets); and every
  // page of both lists. For `lib` and `app` also each node's path (a link
  // naming a pick opens the trees down to it) and each node picked in a tree:
  // the trees it narrows or marks and the lists narrowed by it, and the incoming list
  // for a user picked with a part (types). `lib` is used by `app` and uses
  // nothing outside itself; `app` uses `lib`; `isolated` has neither; the
  // module uses External Types. And `app` and `lib` selected together, a
  // group (#85): app → lib is inside it; with each node picked in a side
  // tree, the trees it marks, the list it narrows and whether each selected
  // node reaches it.
  // Every node's path is recorded, too: ⌘-click asks for the paths of the
  // node it adds and the selected ones.
  const idOf = (text: string) => {
    for (const [key, nodes] of levels) {
      if (!key.startsWith("null|")) continue;
      const node = nodes.find((candidate) => candidate.text === text);
      if (node) return node.id;
    }
    throw new Error(`No "${text}" in the tree`);
  };
  const dependencySets: Entry[] = [];
  const dependencyChildren: Entry[] = [];
  const dependencyPages: Entry[] = [];
  const dependencyPickPaths: Entry[] = [];
  const recordedRequests = new Set<string>();
  type Side = "SOURCE" | "TARGET";
  type Picks = { source?: string; target?: string };
  type Direction = "incoming" | "outgoing";
  const setField = (direction: Direction) =>
    direction === "incoming" ? "incomingDependencies" : "outgoingDependencies";
  const selectionOf = (picks: Picks) => [
    ...(picks.source
      ? [
          {
            selectedNodeIds: [picks.source],
            selectedNodesType: "SOURCE" as const,
          },
        ]
      : []),
    ...(picks.target
      ? [
          {
            selectedNodeIds: [picks.target],
            selectedNodesType: "TARGET" as const,
          },
        ]
      : []),
  ];
  type SetData = {
    hierarchicalGraph: {
      rootNode: TreeNode;
      nodes: {
        nodes: TreeNode[];
        incomingDependencies: { size: number };
        outgoingDependencies: { size: number };
      };
    };
  };
  type ChildrenData = {
    hierarchicalGraph: {
      nodes: Record<
        string,
        | {
            filteredDependencies: {
              filteredChildrenWithCounts: { node: TreeNode }[];
            };
          }
        | undefined
      >;
    };
  };
  type PageData = {
    hierarchicalGraph: {
      nodes: Record<
        string,
        | {
            filteredDependencies: {
              dependencyPage: { pageInfo: { maxPages: number } };
            };
          }
        | undefined
      >;
    };
  };
  const PAGE_SIZE = 50; // DEPENDENCY_PAGE_SIZE in start/queries.ts
  const expandable = (node: TreeNode) =>
    node.hasChildren && !TYPE_KINDS.has(node.type);
  // A level's source, as start/DependencyTrees.tsx has it.
  type LevelSource = { direction: Direction; picks: Picks; side: Side };
  const LIB = "org.hg.fixture.locations.lib";
  const APP = "org.hg.fixture.locations.app";
  for (const texts of [
    [LIB],
    [APP],
    ["org.hg.fixture.basic.isolated"],
    ["io.hierograph.examples:fixture-app:1.0.0"],
    [APP, LIB],
  ]) {
    const ids = texts.map(idOf);
    const group = ids.length > 1;
    const data = await client.request<SetData>(DependencySetDocument, {
      view: null,
      ids,
    });
    dependencySets.push({
      variables: { view: null, ids },
      data: normalize(data as never),
    });
    const { nodes: selection, rootNode } = data.hierarchicalGraph;
    const incoming = selection.incomingDependencies.size;
    const outgoing = selection.outgoingDependencies.size;
    const children = async (source: LevelSource, parent: TreeNode) => {
      const variables = {
        view: null,
        ids,
        incoming: source.direction === "incoming",
        picks: selectionOf(source.picks),
        parent: parent.id,
        side: source.side,
      };
      const level = await client.request<ChildrenData>(
        DependencySetChildrenDocument,
        variables,
      );
      const key = JSON.stringify(variables);
      if (!recordedRequests.has(key)) {
        recordedRequests.add(key);
        dependencyChildren.push({ variables, data: normalize(level as never) });
      }
      return (
        level.hierarchicalGraph.nodes[setField(source.direction)]
          ?.filteredDependencies.filteredChildrenWithCounts ?? []
      ).map((child) => child.node);
    };
    // A tree, every level that can be opened: each level the union of its
    // sources' children, or with `intersect` their intersection.
    const walk = async (
      sources: LevelSource[],
      intersect: boolean,
      top: TreeNode,
      shown: boolean,
    ) => {
      const seen: TreeNode[] = shown ? [top] : [];
      const below = async (parent: TreeNode) => {
        const levels: TreeNode[][] = [];
        for (const source of sources)
          levels.push(await children(source, parent));
        const merged = intersect
          ? (levels[0] ?? []).filter((candidate) =>
              levels.every((level) =>
                level.some((other) => other.id === candidate.id),
              ),
            )
          : [...new Map(levels.flat().map((n) => [n.id, n])).values()];
        for (const child of merged) {
          seen.push(child);
          if (expandable(child)) await below(child);
        }
      };
      if (sources.length > 0 && expandable(top)) await below(top);
      return seen;
    };
    const pages = async (direction: Direction, picks: Picks, all: boolean) => {
      for (let page = 1; ; page++) {
        const variables = {
          view: null,
          ids,
          incoming: direction === "incoming",
          picks: selectionOf(picks),
          picked: [picks.source, picks.target].filter(Boolean),
          page,
          pageSize: PAGE_SIZE,
        };
        const key = JSON.stringify(variables);
        const result = await client.request<PageData>(
          DependencySetPageDocument,
          variables,
        );
        if (!recordedRequests.has(key)) {
          recordedRequests.add(key);
          dependencyPages.push({ variables, data: normalize(result as never) });
        }
        const maxPages =
          result.hierarchicalGraph.nodes[setField(direction)]
            ?.filteredDependencies.dependencyPage.pageInfo.maxPages ?? 0;
        if (!all || page >= maxPages) break;
      }
    };
    // The trees for the picks, as the screen builds them.
    type TreePicks = { usedBy?: string; part?: string; uses?: string };
    const usedByTree = (picks: TreePicks) =>
      incoming === 0
        ? Promise.resolve([])
        : walk(
            [
              {
                direction: "incoming",
                picks: { target: picks.part },
                side: "SOURCE",
              },
            ],
            false,
            rootNode,
            false,
          );
    const usesTree = (picks: TreePicks) =>
      outgoing === 0
        ? Promise.resolve([])
        : walk(
            [
              {
                direction: "outgoing",
                picks: { source: picks.part },
                side: "TARGET",
              },
            ],
            false,
            rootNode,
            false,
          );
    const selectionTree = async (picks: TreePicks) => {
      const sources: LevelSource[] = [];
      if (
        incoming > 0 &&
        !(picks.uses !== undefined && picks.usedBy === undefined)
      )
        sources.push({
          direction: "incoming",
          picks: { source: picks.usedBy },
          side: "TARGET",
        });
      if (
        outgoing > 0 &&
        !(picks.usedBy !== undefined && picks.uses === undefined)
      )
        sources.push({
          direction: "outgoing",
          picks: { target: picks.uses },
          side: "SOURCE",
        });
      const intersect = picks.usedBy !== undefined && picks.uses !== undefined;
      const trees: TreeNode[] = [];
      for (const top of selection.nodes)
        trees.push(...(await walk(sources, intersect, top, true)));
      return trees;
    };
    if (incoming === 0 && outgoing === 0) {
      await pages("incoming", {}, false);
      await pages("outgoing", {}, false);
      continue;
    }
    const users = await usedByTree({});
    const parts = await selectionTree({});
    const used = await usesTree({});
    await pages("incoming", {}, true);
    await pages("outgoing", {}, true);
    if (group) {
      // A side pick marks the selected nodes it reaches (start/
      // DependencyTrees.tsx, `TopRow`) and the parts below them.
      for (const user of users) {
        await selectionTree({ usedBy: user.id });
        await pages("incoming", { source: user.id }, true);
        for (const top of selection.nodes)
          await pages("incoming", { source: user.id, target: top.id }, false);
      }
      for (const target of used) {
        await selectionTree({ uses: target.id });
        await pages("outgoing", { target: target.id }, true);
        for (const top of selection.nodes)
          await pages("outgoing", { source: top.id, target: target.id }, false);
      }
      continue;
    }
    if (texts[0] !== LIB && texts[0] !== APP) continue;
    // A link that names a pick opens the trees down to it.
    for (const pickable of [...users, ...parts, ...used]) {
      const variables = { view: null, id: pickable.id };
      const key = `path|${pickable.id}`;
      if (recordedRequests.has(key)) continue;
      recordedRequests.add(key);
      const path = await client.request(DependencyPickPathDocument, variables);
      dependencyPickPaths.push({ variables, data: normalize(path as never) });
    }
    // A user or a used node sets the parts it connects to in bold: the
    // selection's tree narrowed by it, asked for below the bold rows only.
    for (const user of users) {
      const reached = await selectionTree({ usedBy: user.id });
      await pages("incoming", { source: user.id }, true);
      if (!TYPE_KINDS.has(user.type)) continue;
      for (const part of reached) {
        if (TYPE_KINDS.has(part.type))
          await pages("incoming", { source: user.id, target: part.id }, false);
      }
    }
    for (const target of used) {
      await selectionTree({ uses: target.id });
      await pages("outgoing", { target: target.id }, true);
    }
    // A part sets what it connects to in bold on both sides, the same way.
    for (const part of parts) {
      await usedByTree({ part: part.id });
      await usesTree({ part: part.id });
      await pages("incoming", { target: part.id }, true);
      await pages("outgoing", { source: part.id }, true);
    }
  }
  // Every node's path in the scanned hierarchy, for ⌘-click.
  for (const [key, nodes] of levels) {
    if (!key.startsWith("null|")) continue;
    for (const node of nodes) {
      const pathKey = `path|${node.id}`;
      if (recordedRequests.has(pathKey)) continue;
      recordedRequests.add(pathKey);
      const variables = { view: null, id: node.id };
      const path = await client.request(DependencyPickPathDocument, variables);
      dependencyPickPaths.push({ variables, data: normalize(path as never) });
    }
  }
  write("DependencySet", dependencySets);
  write("DependencySetChildren", dependencyChildren);
  write("DependencySetPage", dependencyPages);
  write("DependencyPickPath", dependencyPickPaths);

  // The structure map: the root in both views, and the levelization of every
  // container (a node with children that isn't a type, as the map has it in
  // structure/structureLabels.ts), the root's included.
  const structureRoots: Entry[] = [];
  const structureLevels: Entry[] = [];
  for (const view of [null, "layers"]) {
    const variables = { view };
    const data = await client.request<{
      hierarchicalGraph: { rootNode: { id: string } };
    }>(StructureRootDocument, variables);
    structureRoots.push({ variables, data: normalize(data as never) });
    const ids = [data.hierarchicalGraph.rootNode.id];
    for (const [key, nodes] of levels) {
      if (!key.startsWith(`${view}|`)) continue;
      for (const node of nodes) {
        if (node.hasChildren && !TYPE_KINDS.has(node.type)) ids.push(node.id);
      }
    }
    for (const id of [...new Set(ids)].sort()) {
      const levelVariables = { view, id };
      const level = await client.request(
        StructureLevelDocument,
        levelVariables,
      );
      structureLevels.push({
        variables: levelVariables,
        data: normalize(level as never),
      });
    }
  }
  write("StructureRoot", structureRoots);
  write("StructureLevel", structureLevels);

  // The weights between the visible boxes for a hovered box: on the
  // collapsed map (the root's children), and with every container opened
  // along its ancestors, as the tests open them one by one. The visible boxes
  // are those of the open containers whose content isn't shown, as the map
  // has them (structure/mapModel.ts); the levels come folded from the server.
  type Node = { id: string; type: string; hasChildren: boolean };
  type Level = {
    hierarchicalGraph: {
      node: {
        children: {
          levelization: {
            levels: {
              nodes: Node[];
            }[];
          };
        };
      };
    };
  };
  const levelOf = (view: string | null, id: string) =>
    structureLevels.find(
      (entry) => entry.variables.view === view && entry.variables.id === id,
    )?.data as Level | undefined;
  const frontierOf = (
    view: string | null,
    rootId: string,
    open: Set<string>,
  ) => {
    const ids: string[] = [];
    const childrenOf = (id: string) =>
      (
        levelOf(view, id)?.hierarchicalGraph.node.children.levelization
          .levels ?? []
      ).flatMap((row) => row.nodes);
    const visit = (id: string) => {
      for (const node of childrenOf(id)) {
        const container = node.hasChildren && !TYPE_KINDS.has(node.type);
        if (container && open.has(node.id) && levelOf(view, node.id))
          visit(node.id);
        else ids.push(node.id);
      }
    };
    visit(rootId);
    return ids.sort();
  };
  const cellEntries: Entry[] = [];
  const cellKeys = new Set<string>();
  for (const [index, view] of [null, "layers"].entries()) {
    const rootId = (
      structureRoots[index].data as {
        hierarchicalGraph: { rootNode: { id: string } };
      }
    ).hierarchicalGraph.rootNode.id;
    // Each recorded container's parent, from the levels.
    const parents = new Map<string, string>();
    for (const entry of structureLevels) {
      if (entry.variables.view !== view) continue;
      const parent = entry.variables.id as string;
      const level = entry.data as Level;
      for (const row of level.hierarchicalGraph.node.children.levelization
        .levels) {
        for (const node of row.nodes) parents.set(node.id, parent);
      }
    }
    const opened = [new Set<string>()];
    for (const entry of structureLevels) {
      const id = entry.variables.id as string;
      if (entry.variables.view !== view || id === rootId) continue;
      const open = new Set<string>();
      for (let at: string | undefined = id; at && at !== rootId;) {
        open.add(at);
        at = parents.get(at);
      }
      opened.push(open);
    }
    // And each of the root's children with everything in it open, as
    // "Expand all" opens it.
    const recorded = structureLevels
      .filter((entry) => entry.variables.view === view)
      .map((entry) => entry.variables.id as string);
    for (const top of recorded) {
      if (parents.get(top) !== rootId) continue;
      const open = new Set<string>();
      for (const id of recorded) {
        for (let at: string | undefined = id; at && at !== rootId;) {
          if (at === top) {
            open.add(id);
            break;
          }
          at = parents.get(at);
        }
      }
      opened.push(open);
    }
    for (const open of opened) {
      const ids = frontierOf(view, rootId, open);
      const key = `${view}|${ids.join()}`;
      if (ids.length < 2 || cellKeys.has(key)) continue;
      cellKeys.add(key);
      const variables = { view, ids };
      const data = await client.request(StructureCellsDocument, variables);
      cellEntries.push({ variables, data: normalize(data as never) });
    }
  }
  write("StructureCells", cellEntries);

  // Dependencies handed to the map (`dependency_ids`), in both views: types
  // by qualified name, so the tests take the ids from this file. Two that
  // exist, one in the cycle, one pair without a dependency, and one id that
  // names no node.
  const idOfText = new Map<string, string>();
  for (const entry of structureLevels) {
    if (entry.variables.view !== null) continue;
    for (const row of (entry.data as Level).hierarchicalGraph.node.children
      .levelization.levels) {
      for (const node of row.nodes as (Node & { text?: string })[]) {
        if (node.text) idOfText.set(node.text, node.id);
      }
    }
  }
  const typeId = (text: string) => {
    const id = idOfText.get(`org.hg.fixture.${text}`);
    if (!id) throw new Error(`No type org.hg.fixture.${text} in the levels`);
    return id;
  };
  const given = [
    ["locations.app.web.OrderController", "locations.lib.order.OrderService"],
    [
      "locations.app.web.CustomerController",
      "locations.lib.customer.CustomerApi",
    ],
    ["basic.cycle.beta.CycleB", "basic.cycle.gamma.CycleC"],
    ["basic.isolated.Island", "basic.core.PlainClass"],
  ].map(([source, target]) => ({
    sourceId: typeId(source),
    targetId: typeId(target),
  }));
  given.push({ sourceId: "424242", targetId: given[0].targetId });
  const givenEntries: Entry[] = [];
  for (const view of [null, "layers"]) {
    const variables = { view, pairs: given };
    const data = await client.request(StructureGivenDocument, variables);
    givenEntries.push({ variables, data: normalize(data as never) });
  }
  write("StructureGiven", givenEntries);
}
