# Proxy Dependencies

## Purpose

`HGProxyDependency` implements a lazy-loading proxy pattern for dependencies that are too expensive to fully resolve upfront.

In a large codebase, there can be thousands of fine-grained dependencies between modules. Loading all of them eagerly during graph construction would be slow (many DB queries), memory-intensive (materializing all edges), and wasteful (users only drill into a few dependencies interactively).

The proxy pattern lets you build the hierarchical graph quickly with coarse-grained edges, then lazily resolve the details only when the user actually inspects a specific dependency.

## Model

```
AbstractHGDependency
  └── HGCoreDependency
        └── HGProxyDependency
              - isResolved() : boolean
              - getResolvedCoreDependencies() : List<HGCoreDependency>
              - resolveProxyDependencies() : void
```

A `HGProxyDependency` acts as a placeholder for a single high-level dependency edge (e.g., "package A depends on package B") that, when resolved, expands into multiple concrete `HGCoreDependency` edges (e.g., specific method calls, field accesses, type references).

## Resolution Flow

1. **Initial graph creation** — the mapping layer creates `HGProxyDependency` instances as lightweight placeholders (one per aggregated relationship from the graph DB).
2. **On demand** — when a user drills into a dependency, `resolveProxyDependencies()` is called.
3. **Resolution** — the `IProxyDependencyResolver` SPI is invoked (implemented by `CustomProxyDependencyResolver` in `mapping.service`), which queries the graph DB via Cypher to fetch the detailed edges.
4. **Result** — `getResolvedCoreDependencies()` returns the now-materialized list of fine-grained `HGCoreDependency` instances.

The `isResolved()` flag tracks whether resolution has already happened, avoiding redundant DB calls.

## How to Define Proxy Dependencies

Proxy dependencies are defined in subclasses of `AbstractQueryBasedDependencyProvider` using the `addProxyDependencyDefinitions()` method. Two types of queries are required:

### Proxy Query (coarse-grained)

Defines the high-level edges that appear initially in the graph. Must return 5 columns:

| Column | Description |
|--------|-------------|
| `id(sourceNode)` | Neo4j node ID of the dependency source |
| `id(targetNode)` | Neo4j node ID of the dependency target |
| `id(relationship)` | Neo4j relationship ID |
| `type(relationship)` | Relationship type as a string |
| `weight` | Integer weight for the dependency |

### Detail Query (fine-grained)

Resolves the proxy into concrete edges on demand. Also returns 5 columns (same format as above). Detail queries must use `$from` and `$to` parameters, which are populated with the Neo4j node IDs of all descendants of the source and target nodes respectively.

## Example

```java
public class MyDependencyProvider extends AbstractQueryBasedDependencyProvider {

    @Override
    protected void initialize() {
        // Simple (eagerly resolved) dependencies — same 5-column format:
        addSimpleDependencyDefinitions(
            "MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type) " +
            "RETURN id(t1), id(t2), id(r), type(r), r.weight"
        );

        // Proxy (lazily resolved) dependencies:
        addProxyDependencyDefinitions(
            // Proxy query — coarse-grained edges:
            "MATCH (p1:Package)-[r:DEPENDS_ON]->(p2:Package) " +
            "RETURN id(p1), id(p2), id(r), type(r), r.weight",
            // Detail queries — fine-grained edges resolved on demand:
            new String[] {
                "MATCH (t1:Type)-[r:INVOKES]->(t2:Type) " +
                "WHERE id(t1) IN $from AND id(t2) IN $to " +
                "RETURN id(t1), id(t2), id(r), type(r), r.weight"
            }
        );
    }
}
```

## Key Classes

| Class | Module | Role |
|-------|--------|------|
| `HGProxyDependency` | `core.model` | Interface — the proxy dependency model element |
| `ExtendedHGProxyDependencyImpl` | `core.model` | Implementation with resolution logic |
| `IProxyDependencyResolver` | `core.model` (SPI) | SPI for plugging in resolution strategies |
| `AbstractQueryBasedDependencyProvider` | `mapping.cypher` | Base class for Cypher-based dependency providers |
| `ProxyDependencyDefinitionImpl` | `mapping.cypher` | Holds proxy definition + resolve function |
| `CustomProxyDependencyResolver` | `mapping.service` | Resolves proxies by executing detail queries |
| `GraphFactoryFunctions` | `mapping.service` | Wires the resolve function onto the proxy during graph construction |
