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

## Using curl

You can also query the endpoint directly from the command line:

```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ hierarchicalGraph { rootNode { id text type children { nodes { id text type } } } } }"}'
```

## Schema overview

The full schema is defined in `hierograph-mcp/io.hierograph.graphql/src/main/resources/graphql/`.
The main types are:

- **`HierarchicalGraph`** — entry point; provides access to the root node and node/dependency
  lookups
- **`Node`** — a node in the hierarchical graph (module, package, class, method, etc.)
- **`NodeSet`** — a collection of nodes with operations for adjacency matrices, referenced/
  referencing node queries, and filtering
- **`Dependency`** — a directed dependency between two nodes with a type and weight
- **`DependencySet`** / **`FilteredDependencies`** — dependency collections with pagination and
  filtering support
- **`OrderedAdjacencyMatrix`** — a dependency structure matrix with cycle detection
