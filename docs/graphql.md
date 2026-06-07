# GraphQL Interface

Hierograph exposes a GraphQL API alongside the MCP server. It provides the same hierarchical graph
data — nodes, dependencies, adjacency matrices — through a standard GraphQL endpoint that any
GraphQL client can query.

## Endpoints

| Endpoint | Description |
|---|---|
| `http://localhost:8080/graphql` | GraphQL API |
| `http://localhost:8080/graphiql/index.html` | Interactive GraphiQL explorer |

Both are available as soon as the MCP server is running (see [Getting Started](getting-started.md),
Steps 4–5).

## Using GraphiQL

GraphiQL is a browser-based IDE for writing and executing GraphQL queries. Open
`http://localhost:8080/graphiql/index.html` in your browser. The left pane is the query editor, and
the **Explorer** sidebar lets you build queries by clicking through the schema.

### Example queries

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

The full schema is defined in `hierograph-mcp/io.hierograph.graphql/src/main/resources/graphql/`. The
`Query` root exposes a single field, `hierarchicalGraph`, from which everything else is reachable.

**`HierarchicalGraph`** — the entry point:

| Field | Returns | Description |
|---|---|---|
| `identifier` | `ID!` | Identifier of the root node. |
| `globalIdentifier` | `ID!` | The hierarchy's name, falling back to the root identifier. |
| `rootNode` | `Node!` | The root of the tree. |
| `node(id)` | `Node` | A single node by id. |
| `nodes(ids)` | `NodeSet!` | A set of nodes by id. |
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

**Enums and inputs:** `NodeType` (`SOURCE`, `TARGET`); `NodesToConsider` (`SELF`,
`SELF_AND_CHILDREN`, `SELF_AND_SUCCESSORS`), used by the `filter*` fields; the `NodeSelection` input
(`selectedNodeIds`, `selectedNodesType`) consumed by `filteredDependencies`; and `MapEntry`, a
`{ key, value }` pair.
