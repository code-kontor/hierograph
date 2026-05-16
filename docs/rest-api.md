# Cartograph REST API

The Cartograph server exposes a REST API alongside the MCP interface. All endpoints are served
under `/api` and use HTTP GET with query parameters. The server runs on port `8080` by default.

For setup instructions, see the [Getting Started](getting-started.md) guide. For an overview of
how Cartograph fits into the larger architecture, see the
[Architecture Overview](cartograph-architecture-overview.md).

**Base URL:** `http://localhost:8080/api`

## Endpoints

### Graph Overview

#### `GET /api/describe-graph`

Returns a structured overview of the loaded graph or a specific scope.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `scopeId` | Long | no | Scope node ID to describe. Omit for full graph overview. |

```bash
# Full graph overview
curl "http://localhost:8080/api/describe-graph"

# Overview of a specific artifact
curl "http://localhost:8080/api/describe-graph?scopeId=675"
```

### Node Lookup

#### `GET /api/find-node`

Look up nodes by name using case-insensitive substring matching.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `query` | String | yes | Name or fragment to search for |
| `kind` | String | no | Filter by kind: `Class`, `Interface`, `Enum`, `Annotation`,`Record`,`Package`, `Artifact` |
| `limit` | Integer | no | Max results (1-50, default 10) |

```bash
# Find all nodes matching "UserService"
curl "http://localhost:8080/api/find-node?query=UserService"

# Find only interfaces matching "Repository"
curl "http://localhost:8080/api/find-node?query=Repository&kind=Interface&limit=20"
```

#### `GET /api/list-children`

Returns the immediate direct children of a node (one level only).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `nodeId` | Long | no | Node ID. Omit for root-level nodes. |
| `limit` | Integer | no | Max results (1-200, default 50) |

```bash
# List root-level nodes
curl "http://localhost:8080/api/list-children"

# List children of a specific node
curl "http://localhost:8080/api/list-children?nodeId=675"
```

#### `GET /api/list-descendants`

Returns all descendants of a node matching filters.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `rootId` | Long | yes | Root node ID |
| `kindFilter` | List\<String\> | no | Node kinds to include (OR logic) |
| `excludeKindFilter` | List\<String\> | no | Node kinds to exclude |
| `limit` | Integer | no | Max results (1-5000, default 500) |

```bash
# All classes and interfaces under a package
curl "http://localhost:8080/api/list-descendants?rootId=6597&kindFilter=Class&kindFilter=Interface"

# Everything except packages
curl "http://localhost:8080/api/list-descendants?rootId=675&excludeKindFilter=Package"
```

### Dependency Analysis

#### `GET /api/dependency-between`

Checks whether a dependency exists between two subtrees.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `fromId` | Long | yes | Source node ID |
| `toId` | Long | yes | Target node ID |

```bash
curl "http://localhost:8080/api/dependency-between?fromId=680&toId=675"
```

#### `GET /api/aggregated-outgoing`

Aggregated outgoing dependencies from a source node.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `sourceId` | Long | yes | Source node ID |
| `targetScopeId` | Long | no | Scope whose children are target candidates. Omit for top-level. |
| `limit` | Integer | no | Max edges (1-100, default 20) |

```bash
curl "http://localhost:8080/api/aggregated-outgoing?sourceId=675"
```

#### `GET /api/aggregated-incoming`

Aggregated incoming dependencies to a target node (blast radius).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `targetId` | Long | yes | Target node ID |
| `sourceScopeId` | Long | no | Scope whose children are source candidates. Omit for top-level. |
| `limit` | Integer | no | Max edges (1-100, default 20) |

```bash
# Who depends on HGRootNode?
curl "http://localhost:8080/api/aggregated-incoming?targetId=6837"
```

#### `GET /api/outgoing-core-dependencies`

Concrete leaf-level dependencies from one subtree to another.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `fromId` | Long | yes | Source subtree root node ID |
| `toId` | Long | yes | Target subtree root node ID |
| `limit` | Integer | no | Max edges (1-100, default 20) |

```bash
curl "http://localhost:8080/api/outgoing-core-dependencies?fromId=680&toId=6837"
```

#### `GET /api/incoming-core-dependencies`

Concrete leaf-level dependencies into a subtree from another.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `toId` | Long | yes | Target subtree root node ID |
| `fromId` | Long | yes | Source subtree root node ID |
| `limit` | Integer | no | Max edges (1-100, default 20) |

```bash
curl "http://localhost:8080/api/incoming-core-dependencies?toId=6837&fromId=680"
```

#### `GET /api/find-dependency-path`

Finds the shortest transitive dependency path between two nodes.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `fromId` | Long | yes | Source node ID |
| `toId` | Long | yes | Target node ID |
| `maxLength` | Integer | no | Maximum path length (1-20, default 10) |

```bash
curl "http://localhost:8080/api/find-dependency-path?fromId=680&toId=667"
```

#### `GET /api/affected-by`

Transitive blast radius analysis -- what is affected if a node changes?

| Parameter | Type | Required | Description |
|---|---|---|---|
| `sourceId` | Long | yes | Source node ID |
| `maxDepth` | Integer | no | Max traversal depth (1-20, default 5) |
| `groupingScopeId` | Long | no | Grouping scope for aggregation |
| `topN` | Integer | no | Size of top affected list (1-50, default 10) |

```bash
curl "http://localhost:8080/api/affected-by?sourceId=6837&maxDepth=3&topN=5"
```

### Multi-Target Analysis

#### `GET /api/outgoing-to`

Checks whether a source node has dependencies to each of a list of target nodes.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `sourceId` | Long | yes | Source node ID |
| `targetIds` | List\<Long\> | yes | Target node IDs (max 50) |
| `includeMissing` | Boolean | no | Include targets with no dependency (default true) |

```bash
curl "http://localhost:8080/api/outgoing-to?sourceId=675&targetIds=667&targetIds=680"
```

#### `GET /api/incoming-from`

Checks which of a list of source nodes depend on a target node.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `targetId` | Long | yes | Target node ID |
| `sourceIds` | List\<Long\> | yes | Source node IDs (max 50) |
| `includeMissing` | Boolean | no | Include sources with no dependency (default true) |

```bash
curl "http://localhost:8080/api/incoming-from?targetId=6837&sourceIds=675&sourceIds=680"
```

#### `GET /api/pairwise-dependencies`

Dependency Structure Matrix (DSM) -- pairwise dependency analysis for a set of nodes.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `nodeIds` | List\<Long\> | yes | Node IDs to analyze |
| `includeSelfLoops` | Boolean | no | Include self-loops (default false) |

```bash
curl "http://localhost:8080/api/pairwise-dependencies?nodeIds=675&nodeIds=680&nodeIds=667"
```

## Notes

- All endpoints return JSON.
- Node IDs are internal graph identifiers. Use `/api/find-node` to look up IDs by name.
- The REST API mirrors the MCP tools exposed to Claude -- both are backed by the same
  `GraphMcpTools` implementation.
