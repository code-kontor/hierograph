# GraphQL Interface

Hierograph exposes a GraphQL API alongside the MCP server. It provides the same hierarchical graph
data — nodes, dependencies, adjacency matrices — through a standard GraphQL endpoint that any
GraphQL client can query.

## Endpoints

| Endpoint | Description |
|---|---|
| `http://localhost:8080/graphql` | GraphQL API |
| `http://localhost:8080/graphiql/index.html` | Interactive GraphiQL explorer |

Both are available as soon as the MCP server is running (see
[Running the MCP server](../BUILD.md#running-the-mcp-server)).

## Using GraphiQL

GraphiQL is a browser-based IDE for writing and executing GraphQL queries. Open
`http://localhost:8080/graphiql/index.html` in your browser. The left pane is the query editor, and
the **Explorer** sidebar lets you build queries by clicking through the schema.

### Example queries

**Find nodes by name** (the entry point when you don't have an id yet; same matching and
ranking as the MCP `find_node` tool):

```graphql
{
  hierarchicalGraph {
    findNodes(name: "invoice", kinds: ["types"], limit: 10) {
      id
      text
      type
    }
  }
}
```

**Get the root node and its direct children:**

```graphql
{
  hierarchicalGraph {
    identifier
    rootNode {
      id
      text
      type
      children {
        nodes {
          id
          text
          type
        }
      }
    }
  }
}
```

**Look up a specific node by ID:**

```graphql
{
  hierarchicalGraph {
    node(id: "42") {
      id
      text
      type
      parent {
        id
        text
      }
      hasChildren
      properties {
        key
        value
      }
    }
  }
}
```

**Get the dependency structure matrix for a set of nodes:**

```graphql
{
  hierarchicalGraph {
    nodes(ids: ["100", "200", "300"]) {
      orderedAdjacencyMatrix {
        orderedNodes {
          id
          text
        }
        cells {
          row
          column
          value
        }
        stronglyConnectedComponents {
          nodeIds
          nodePositions
        }
      }
    }
  }
}
```

**Get dependencies between two nodes:**

```graphql
{
  hierarchicalGraph {
    dependencySetForAggregatedDependency(sourceNodeId: "100", targetNodeId: "200") {
      size
      dependencies {
        sourceNode { id text }
        targetNode { id text }
        type
        weight
      }
    }
  }
}
```

**Find nodes referenced by a specific node:**

```graphql
{
  hierarchicalGraph {
    node(id: "42") {
      text
      referencedNodes(includePredecessors: false) {
        nodes {
          id
          text
          type
        }
      }
    }
  }
}
```

**Page through a dependency set:**

```graphql
{
  hierarchicalGraph {
    dependencySetForAggregatedDependency(sourceNodeId: "100", targetNodeId: "200") {
      dependencyPage(pageNumber: 1, pageSize: 20) {
        pageInfo {
          pageNumber
          maxPages
          pageSize
          totalCount
        }
        dependencies {
          sourceNode { id }
          targetNode { id }
          weight
        }
      }
    }
  }
}
```

**List the dependencies from one node to specific targets:**

```graphql
{
  hierarchicalGraph {
    node(id: "42") {
      dependenciesTo(targetNodes: ["100", "200"]) {
        targetNode { id text }
        type
        weight
      }
    }
  }
}
```

## Using curl

You can also query the endpoint directly from the command line:

```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ hierarchicalGraph { rootNode { id text type children { nodes { id text type } } } } }"}'
```

## Schema overview

The full schema is defined in `hierograph-server/io.hierograph.server.graphql/src/main/resources/graphql/`. The
`Query` root exposes a single field, `hierarchicalGraph`, from which everything else is reachable.

**`HierarchicalGraph`** — the entry point:

| Field | Returns | Description |
|---|---|---|
| `identifier` | `ID!` | Identifier of the root node. |
| `rootNode` | `Node!` | The root of the tree. |
| `node(id)` | `Node` | A single node by id. |
| `nodes(ids)` | `NodeSet` | A set of nodes by id. |
| `findNodes(name, kinds, limit)` | `[Node!]!` | Nodes by name, best match first; `kinds` filters by kind or group alias (`types`, `members`, `packages`), `limit` is 1–200 (default 50). |
| `dependency(id)` | `Dependency` | A single dependency by its `<from>_<to>_<type>` id. |
| `dependencies(ids)` | `DependencySet` | A set of dependencies by id. |
| `dependencySetForAggregatedDependency(sourceNodeId, targetNodeId)` | `DependencySet` | All core dependencies rolled up between two subtrees. |

**`Node`** — a node in the hierarchical graph (module, package, class, method, …): `id`, `text`,
`type`, `parent`, `predecessors`, `hasChildren`, `children: NodeSet!`, `properties: [MapEntry!]!`,
`childrenFilteredByReferencedNodes` / `childrenFilteredByReferencingNodes`, `dependenciesTo` /
`dependenciesFrom`, `referencedNodes` / `referencingNodes`, and `filterReferencedNodes` /
`filterReferencingNodes`.

**`NodeSet`** — a collection of nodes: `nodes`, `nodeIds`, `orderedAdjacencyMatrix`, `referencedNodes`
/ `referencingNodes`, and `filterReferencedNodes` / `filterReferencingNodes`.

**`Dependency`** — a directed dependency between two nodes: `id`, `sourceNode`, `targetNode`, `type`,
`weight`.

**`DependencySet`** and **`FilteredDependencies`** — dependency collections with `size`,
`dependencies`, and `dependencyPage(pageNumber, pageSize)` (returns a `DependencyPage` carrying
`PageInfo`). `DependencySet` adds `filteredChildren` / `filteredChildrenIds` and `filteredDependencies`;
`FilteredDependencies` adds `nodes` / `nodeIds` / `referencedNodes` / `referencedNodeIds`.

**`OrderedAdjacencyMatrix`** — a dependency structure matrix with cycle detection: `orderedNodes`,
`cells` (each `Cell` is `row` / `column` / `value`), and `stronglyConnectedComponents` (each
`StronglyConnectedComponent` is `nodes` / `nodeIds` / `nodePositions`).

Fields that take node ids match subtrees: a package or module id stands for every
type below it. Every type and field carries a description, shown in GraphiQL's
documentation pane.

**Enums and inputs:** `NodeType` (`SOURCE`, `TARGET`); `NodesToConsider` (`SELF`,
`SELF_AND_CHILDREN`, `SELF_AND_SUCCESSORS`), used by the `filter*` fields; the `NodeSelection` input
(`selectedNodeIds`, `selectedNodesType`) consumed by `filteredDependencies`; and `MapEntry`, a
`{ key, value }` pair.

## Errors

Errors appear in the response's `errors` array, next to whatever data could be
resolved. Each carries a classification in `extensions.classification`:

| Classification | When |
|---|---|
| `BAD_REQUEST` | An argument is invalid: a non-numeric node id, a malformed dependency id, a page number or size below 1, an unknown `findNodes` kind. |
| `NOT_FOUND` | An id names nothing in the served graph. Non-fatal: the field answers without the id, and `extensions.unknownNodeIds` / `unknownDependencyIds` list it. |
| `GRAPH_UNAVAILABLE` | No graph is loaded yet; `hierarchicalGraph` is `null`. |

The full behaviour is specified in the
[GraphQL API specification](../hierograph-server/io.hierograph.server.graphql/spec/hierograph-graphql.md).
