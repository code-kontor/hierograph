import nodeChildrenFixture from "@/testing/fixtures/NodeChildren.json";
import rootNodeFixture from "@/testing/fixtures/RootNode.json";

/**
 * Resolve a node's id from its fully-qualified name (the `text` field) using
 * the recorded fixtures.
 *
 * Node ids are assigned by the jQAssistant scan and are **not stable** across
 * scans: adding or removing classes in the fixture-app shifts the ids of
 * unrelated nodes. Fully-qualified names, on the other hand, are stable. Tests
 * must therefore reference nodes by fqn and resolve to an id at runtime rather
 * than hard-coding ids — that way growing or shrinking the fixture-app (and
 * re-recording the generated fixtures) never touches the test sources.
 *
 * The lookup walks the recorded RootNode + NodeChildren fixtures, i.e. exactly
 * the data the app itself navigates, so a fqn is resolvable iff the tree is
 * reachable in the recorded fixtures.
 */

type FixtureNode = {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
};

const childrenByParentId = new Map<string, FixtureNode[]>();
for (const entry of nodeChildrenFixture.entries) {
  const parentId = (entry.variables as { id: string }).id;
  const nodes =
    (
      entry.data as {
        hierarchicalGraph: { node: { children: { nodes: FixtureNode[] } } };
      }
    ).hierarchicalGraph?.node?.children?.nodes ?? [];
  childrenByParentId.set(parentId, nodes);
}

const rootNode = (
  rootNodeFixture.entries[0].data as {
    hierarchicalGraph: { rootNode: FixtureNode };
  }
).hierarchicalGraph.rootNode;

/**
 * Returns the id of the node whose fqn (`text`) equals `fqn`.
 * Throws a descriptive error if no such node exists in the recorded fixtures.
 */
export function resolveNodeId(fqn: string): string {
  const queue: FixtureNode[] = [rootNode];
  while (queue.length > 0) {
    const node = queue.shift()!;
    if (node.text === fqn) {
      return node.id;
    }
    const children = childrenByParentId.get(node.id);
    if (children) {
      queue.push(...children);
    }
  }
  throw new Error(
    `resolveNodeId: no node with fqn "${fqn}" in the recorded fixtures. ` +
      `If the fixture-app changed, run \`pnpm fixtures:record\`; otherwise check the fqn.`,
  );
}

/** Convenience: resolve several fqns at once, preserving order. */
export function resolveNodeIds(...fqns: string[]): string[] {
  return fqns.map(resolveNodeId);
}
