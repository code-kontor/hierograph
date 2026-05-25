# Issue: Inherited method nodes have null name/fqn — need to follow RESOLVES_TO

## Summary

jQAssistant creates method nodes on types for methods inherited from superinterfaces/superclasses. These inherited method nodes have `null` name and fqn properties in Neo4j, making them unidentifiable through the MCP tools. The nodes carry a `RESOLVES_TO` relationship pointing to the actual method node (on the declaring supertype) that holds the real metadata.

## Neo4j data

Direct query on two affected nodes (IDs 77270, 77278 on `BeanDefinition`):

```
name: null
fqn:  null
labels: ["Java", "ByteCode", "Member", "Method"]
```

Relationships on these nodes:

| Relationship | Direction | Meaning |
|---|---|---|
| `DECLARES` | incoming from `BeanDefinition` | The type "declares" this inherited slot |
| `INHERITED_FROM` | outgoing | Links to the superinterface this method originates from |
| `RESOLVES_TO` | outgoing | **Points to the actual method node with name/metadata** |
| `INVOKES` | incoming | Direct callers of this inherited method |
| `VIRTUAL_INVOKES` | incoming | Virtual dispatch callers of this inherited method |

The `RESOLVES_TO` target is the real method declaration (e.g., `AttributeAccessor.getAttribute()`) which has the proper `name`, `fqn`, parameters, return type, etc.

## MCP tool output for affected nodes

`method_details` for both nodes (77270 and 77278) returns identical default metadata:

| Property | Value |
|---|---|
| `name` | `""` |
| `qualified_name` | `""` |
| `declaring_type` | `BeanDefinition` (`org.springframework.beans.factory.config.BeanDefinition`) |
| `modifiers` | `["package-private"]` |
| `is_constructor` | `false` |
| `return_type` | `void` (primitive) |
| `parameters` | `[]` |
| `throws` | `[]` |
| `annotations` | `[]` |
| `overrides` | `null` |
| `location` | `null` |

Every field is either empty or a default value. None of the actual method metadata (name, return type, parameters) was populated — it all lives on the `RESOLVES_TO` target node.

## Mechanism

1. jQAssistant scans bytecode and creates method nodes for every method a type has — including inherited ones
2. Inherited method nodes get `DECLARES` (from the type), `INHERITED_FROM` (to the supertype), and `RESOLVES_TO` (to the actual declaration)
3. But the inherited node itself has **null** name/fqn — the metadata lives only on the `RESOLVES_TO` target
4. When callers invoke the method through the inheriting type, the edges (`INVOKES` / `VIRTUAL_INVOKES`) point to the inherited node, not the declaring node
5. The hierarchical graph mapping picks up these null-named nodes and exposes them as unnamed methods

## Scope

The issue affects all types with non-trivial inheritance. It scales with inheritance depth/breadth:

| Type | Module | Methods | Unnamed | Inheritance |
|------|--------|---------|---------|-------------|
| `DefaultListableBeanFactory` | spring-beans | 166 | 37 | deep hierarchy + multiple interfaces |
| `ProxyFactory` | spring-aop | 26 | 15 | deep hierarchy |
| `AbstractBeanFactory` | spring-beans | 119 | 17 | `FactoryBeanRegistrySupport` + `ConfigurableBeanFactory` |
| `RequestMappingHandlerMapping` | spring-webmvc | 44 | 8 | deep hierarchy + interfaces |
| `BeanDefinition` | spring-beans | 43 | 5 | `AttributeAccessor`, `BeanMetadataElement` |
| `ApplicationContext` | spring-context | 10 | 4 | 6 superinterfaces |

Types with only `Object` as superclass and no interfaces (e.g., `BeanUtils`, `StringUtils`) have **zero** unnamed methods.

## Impact

- **Detail-level dependency queries** return edges pointing to unnamed methods — callers can't identify the target (e.g., `isCompatible()` → unnamed method at line 378)
- **`list_children`** shows unnamed entries, inflating method counts with unidentifiable members
- **Method-level analysis** is incomplete for any type with inheritance

## What goes wrong — step by step

### Setup: the type hierarchy

```
AttributeAccessor (interface)
  ├── getAttribute()    ← declared here, has name/fqn/params/return type
  ├── setAttribute()
  ├── hasAttribute()
  ├── removeAttribute()
  └── attributeNames()

BeanDefinition (interface) extends AttributeAccessor
  ├── getBeanClassName() ← declared here, has name
  ├── setScope()         ← declared here, has name
  ├── ...                ← 38 named methods total
  ├── (node 74324)       ← inherited stub, name=null, fqn=null
  ├── (node 74332)       ← inherited stub, name=null, fqn=null
  └── ...                ← 5 unnamed stubs total
```

### Step 1: jQAssistant scans the bytecode

jQAssistant creates a method node for every method a type has in bytecode — including inherited ones. For `BeanDefinition`, it creates:

- **38 declared method nodes** with full metadata (`name`, `fqn`, parameters, return type, etc.)
- **5 inherited method nodes** (stubs) with `null` name and `null` fqn

Each inherited stub has a `RESOLVES_TO` edge pointing to the real method on `AttributeAccessor`:

```
(BeanDefinition)-[:DECLARES]->(stub:Method {name: null})
(stub)-[:RESOLVES_TO]->(AttributeAccessor.getAttribute:Method {name: "getAttribute", fqn: "...", ...})
(stub)-[:INHERITED_FROM]->(AttributeAccessor)
```

### Step 2: Caller code invokes the method

When `ClassPathBeanDefinitionScanner.isCompatible()` calls `beanDefinition.getAttribute(...)`, the bytecode references `BeanDefinition.getAttribute`. jQAssistant creates an edge:

```
(isCompatible)-[:INVOKES]->(stub on BeanDefinition)   // NOT the real getAttribute on AttributeAccessor
```

The call edge points to the **inherited stub** (because the bytecode reference is `BeanDefinition.getAttribute`), not to the real declaration on `AttributeAccessor`.

### Step 3: The hierarchical graph mapping picks up the stub

The mapping layer reads all `DECLARES` children of `BeanDefinition` and creates HG method nodes for each. For the 5 stubs, it creates nodes with empty name/fqn because those properties are null in Neo4j. **It does not follow `RESOLVES_TO`.**

### Step 4: MCP tools expose unnamed methods

When a user queries `outgoing_dependencies(ClassPathBeanDefinitionScanner → BeanDefinition, detail)`, the result includes:

```json
{
  "from": "isCompatible",
  "to": 74324,              // ← unnamed stub
  "to_parent": "BeanDefinition",
  "relationship": "calls",
  "location": {"line_number": 378}
}
```

The `nodes` map shows:
```json
"74324": {"name": "", "qualified_name": "", "kind": "java.method"}
```

The user sees that `isCompatible()` calls *something* on `BeanDefinition` at line 378, but cannot identify **what** method it is.

### What it should look like

After the fix, the same query should return:

```json
{
  "from": "isCompatible",
  "to": 74324,
  "to_parent": "BeanDefinition",
  "relationship": "calls",
  "location": {"line_number": 378}
}
```

With the `nodes` map showing:
```json
"74324": {"name": "getAttribute", "qualified_name": "org.springframework.core.AttributeAccessor.getAttribute", "kind": "java.method"}
```

The edge structure stays the same — the stub still exists as the call target (it correctly represents "calling getAttribute through BeanDefinition"). But the stub now carries the resolved name and metadata so the user can identify the method.

## Fix

The hierarchical graph mapping layer should **follow `RESOLVES_TO`** when populating method node metadata. When a method node has null name/fqn:

1. Query the outgoing `RESOLVES_TO` edge from the stub node
2. Read the target node's `name`, `fqn`, and other properties
3. Copy them onto the stub's HG node

This can be done either:
- **At graph construction time:** When building HG nodes from Neo4j, check for null name and resolve eagerly. This is the approach used for the `getSource()` fix.
- **At query time:** When the detail dependency Cypher returns a node with null name, follow `RESOLVES_TO` in a secondary lookup. Less efficient but doesn't require rebuilding the graph.

The graph-construction-time approach is preferred since it fixes the problem everywhere (list_children, method_details, detail dependencies) in one place.

Alternatively, consider whether inherited method stubs should be exposed as first-class children of the type at all, or whether call edges (`INVOKES`/`VIRTUAL_INVOKES`) targeting inherited nodes should be redirected to the `RESOLVES_TO` target during graph construction.

## Concrete example: `BeanDefinition`

`BeanDefinition` extends two superinterfaces:

- **`AttributeAccessor`** — 7 methods total:
  - 5 abstract: `setAttribute`, `getAttribute`, `hasAttribute`, `removeAttribute`, `attributeNames`
  - 1 default: `computeAttribute`
  - 1 synthetic: `lambda$computeAttribute$0`
- **`BeanMetadataElement`** — 1 method: `getSource`

Of the 43 method nodes on `BeanDefinition`, 5 have empty names. These correspond to the 5 **abstract** `AttributeAccessor` methods. The default method `computeAttribute` and its backing lambda do not generate inherited stubs — consistent with the fact that default methods have an implementation in the declaring interface and don't need a separate inherited node.

The 6th inherited method (`BeanMetadataElement.getSource()`) was previously also unresolved but has been fixed by following `RESOLVES_TO` during graph construction.

The remaining 5 unnamed method IDs on `BeanDefinition` (after rescan):

| ID | Expected method (from `AttributeAccessor`) |
|----|---------------------------------------------|
| 74332 | *(one of the 5 below)* |
| 74324 | *(one of the 5 below)* |
| 72944 | *(one of the 5 below)* |
| 72945 | *(one of the 5 below)* |
| 78271 | *(one of the 5 below)* |

Expected methods: `setAttribute`, `getAttribute`, `hasAttribute`, `removeAttribute`, `attributeNames`

The exact mapping cannot be determined from the MCP tools since the nodes have no identifying metadata. The `RESOLVES_TO` relationship in Neo4j would confirm the 1:1 correspondence.

## Fix applied so far

`BeanMetadataElement.getSource()` has been fixed — the `RESOLVES_TO` target is now followed to populate name/metadata for this inherited method on `BeanDefinition`. The same approach needs to be generalized to all inherited method nodes (including the 5 remaining `AttributeAccessor` methods and all other affected types).
