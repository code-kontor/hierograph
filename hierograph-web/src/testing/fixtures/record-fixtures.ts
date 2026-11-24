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
  GraphStatusDocument,
  HierarchyArcsDocument,
  HierarchyTreeChildrenDocument,
  HierarchyTreeRootDocument,
  HierarchyViewsDocument,
  StartScreenDocument,
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
  { name: "StartScreen", document: StartScreenDocument },
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
