# Hierograph: Architecture and Extensibility

This document describes Hierograph's internal architecture — specifically the three-provider model that separates scanner-specific concerns from the model and tool layers. It is complementary to the tool surface proposal: the tool surface proposal documents the external API the LLM sees; this document describes how the system is structured internally to make that API scanner-agnostic.

The intended audience is implementers and contributors. End users don't need to know any of this; the architecture is deliberately invisible to them.

## The three layers

Hierograph is organized as three layers, each with a clear responsibility and a clean interface to the layers around it.

```
┌─────────────────────────────────────────┐
│           Tool layer                    │
│   (MCP tools — scanner-agnostic)        │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│           Model layer                   │
│  (in-memory hierarchical graph —        │
│       scanner-agnostic)                 │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│         Provider layer                  │
│   (scanner-specific — knows about       │
│      jQAssistant, or future scanners)   │
└─────────────────────────────────────────┘
                  ↓
                Neo4j
```

**The tool layer** implements the MCP tools (`find_node`, `list_children`, `aggregated_dependencies`, etc.). It calls into the model layer. It never knows about Cypher, Neo4j, or the specific scanner.

**The model layer** is Hierograph's in-memory representation: the hierarchy tree, the type-level dependency edges, the cached node properties. It exposes operations the tool layer needs (traversal, aggregation, reachability). It calls into the provider layer when it needs data from the underlying graph. It never knows about the scanner's specifics.

**The provider layer** knows about exactly one scanner (jQAssistant today). It translates between Hierograph's abstract data needs ("give me the parent-child hierarchy," "give me detail-level call edges between these type sets") and the scanner's specific schema ("here's the Cypher query against jQAssistant's labels and relationship types").

The benefit of this separation: a new scanner can be added by writing a new provider implementation. The model and tool layers don't change.

## What lives where

A more concrete mapping:

**Tool layer:**
- Tool implementations (`outgoing_dependencies`, `aggregated_dependencies`, etc.)
- Request parameter validation
- Response shape construction
- Cursor encoding/decoding
- The interface to Spring AI MCP

**Model layer:**
- The in-memory hierarchical graph
- Type-level dependency edges with weights and kinds
- Node property cache (lazy materialization)
- Traversal algorithms (descendants, reachability, paths)
- Aggregation algorithms (pairwise dependency rollup)
- Iteration order definitions (for pagination determinism)

**Provider layer:**
- Cypher queries for loading the hierarchy
- Cypher queries for loading initial core dependencies (type-level)
- Cypher queries for loading node properties on demand
- Cypher queries for detail-level dependencies (calls, throws, reads_field, etc.)
- Cypher queries for entity-detail tools (full method details, full field details)
- The vocabulary of supported relationship kinds for this scanner

The split point is clear: anything that requires knowing the scanner's schema is in the provider. Everything else is in the model or tool layer.

## The three providers

The provider layer is itself decomposed into three sub-providers, each responsible for a specific aspect of the data:

### `HierarchyProvider`

Returns the structural skeleton of the codebase: nodes (modules, packages, types, methods, fields) and their parent-child relationships. Called once at startup to load the in-memory hierarchy.

Conceptually, it provides:
- A query that returns all nodes with their IDs, names, qualified names, and kinds
- A query that returns all parent-child relationships
- These can be a single combined query or separated; depends on implementation

The current jQAssistant implementation uses bulk Cypher queries against jQAssistant's labels (`Module`, `Package`, `Type`, `Method`, `Field`) and containment relationships.

A scanner for another language would provide equivalent queries against its own schema.

### `CoreDependencyProvider`

Returns the type-level dependency edges with their attributes. Called once at startup to load the dependency graph into memory.

Conceptually, it provides:
- A query that returns all type-to-type dependency edges with their weights and structured attribute set
- A declaration of which attribute kinds the scanner supports (e.g., for Java: `is_extends`, `is_implements`, `is_annotated_by`, `is_depends_on_other`)

Each edge between a (source, target) type pair is *one* edge with attributes — not multiple edges per kind. The attributes are flags indicating which specific kinds of underlying detail-level relationships contribute to the edge. This design keeps the in-memory graph topology simple (one edge per pair) while preserving the kind information that would be lost in a generic `depends_on`-only representation.

**Attribute storage uses a bit-packed integer, not a map.** With 115,906 type-level edges on a Spring-sized codebase, the difference between storing attributes as `Map<String, Boolean>` and as a bit-packed `Int` is roughly 30 MB of heap (measured). A four-byte int suffices for vocabularies up to 32 attributes, well beyond any realistic provider's needs. The provider declares its attributes in an ordered list; position N in the list maps to bit N in the edge's bitmap. The bitmap is the storage representation; when edges are serialized to the LLM via JSON, the bits expand to the named map shape using the provider's declared attribute names.

For the Java provider, the loader derives the attribute flags from jQAssistant's specific relationship types. The skeleton of the query joins the canonical `DEPENDS_ON` edge with the more specific `EXTENDS`, `IMPLEMENTS`, and `ANNOTATED_BY` edges, computing a boolean for each:

```cypher
MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type)
OPTIONAL MATCH (t1)-[ext:EXTENDS]->(t2)
OPTIONAL MATCH (t1)-[impl:IMPLEMENTS]->(t2)
OPTIONAL MATCH (t1)-[ann:ANNOTATED_BY]->(t2)
RETURN id(t1) AS source_id,
       id(t2) AS target_id,
       id(r) AS edge_id,
       r.weight AS weight,
       count(ext) > 0 AS is_extends,
       count(impl) > 0 AS is_implements,
       count(ann) > 0 AS is_annotated_by,
       count(ext) = 0 AND count(impl) = 0 AND count(ann) = 0 AS is_depends_on_other
```

(Pseudo-Cypher; the exact pattern depends on jQAssistant's schema and the loader's query style. The `is_depends_on_other` computation is more nuanced — it's true when *some* dependency exists that isn't extends/implements/annotated_by, which means weight > 0 even after accounting for the structural relationships.)

A scanner for another language would provide a query against its own dependency schema, derive its own attribute set, and produce the same edge shape: (source_id, target_id, edge_id, weight, attributes_map). The shape is fixed by Hierograph; the scanner-specific part is how to compute it.

### `DetailDependencyProvider`

Returns detail-level edges on demand. Called whenever the tool layer needs to drill into method/field-level dependencies between two subtrees.

This is the missing piece in the current implementation. Detail-level queries currently live embedded in tool implementations; moving them into a provider completes the three-provider model.

Conceptually, it provides:
- For each detail-level relationship kind the scanner supports, a query that returns edges of that kind between two given sets of type IDs
- A declaration of which relationship kinds this scanner supports
- Queries for the entity-detail tools (full method details, full field details)

The current jQAssistant implementation supports Java-specific relationship kinds: `calls`, `throws`, `returns`, `parameter_type`, `reads_field`, `writes_field`, `overrides`, `annotated_by`, `parameter_annotated_by`, `has_type`, `read_by`, `written_by`.

A Python scanner would support a different vocabulary — probably overlapping (`calls`, `returns`, `parameter_type`) but with Python-specific additions (`decorated_by` rather than `annotated_by`, possibly `imports` as a distinct kind, etc.). The vocabulary is part of what the scanner declares, not a fixed property of Hierograph.

## Provider interface sketches

The actual Kotlin/Java interfaces would look roughly like this. Treat these as sketches, not final signatures — the real interfaces will refine these as implementation progresses.

```kotlin
interface HierarchyProvider {
    /** Returns all nodes with their basic identity. */
    fun nodesQuery(): String

    /** Returns all parent-child relationships. */
    fun parentChildQuery(): String
}

interface CoreDependencyProvider {
    /**
     * The attribute kinds this provider supports, in declaration order.
     * Position in this list determines the bit position used to encode
     * the attribute in each edge's attributesBitmap.
     */
    val supportedAttributeKinds: List<String>

    /**
     * Returns the type-level dependency edges with their attributes.
     * Each row: (sourceId, targetId, edgeId, weight, attributesBitmap)
     * where attributesBitmap is a bit-packed int with one bit per
     * supportedAttributeKinds entry, in declaration order.
     */
    fun typeDependencyQuery(): String
}

/**
 * Shape of a type-level edge in memory.
 *
 * Attributes are stored as a bit-packed int: bit N corresponds to the
 * attribute at position N in the provider's supportedAttributeKinds list.
 * This keeps per-edge memory cost low (4 bytes for attributes vs. ~200
 * bytes for a Map<String, Boolean> with the same information). For Spring-
 * sized codebases with ~30,000 type-level edges, the bitmap representation
 * is approximately 30 MB cheaper than a map-based representation.
 *
 * The bitmap is the storage format. When edges are serialized to the LLM
 * via JSON responses, the bits are expanded into the named Map<String, Boolean>
 * shape using the provider's declared attribute names. The LLM never sees
 * the bitmap directly.
 */
data class TypeLevelEdge(
    val sourceId: Long,
    val targetId: Long,
    val edgeId: Long,
    val weight: Int,
    val attributesBitmap: Int
) {
    fun hasAttribute(name: String, provider: CoreDependencyProvider): Boolean {
        val bitPosition = provider.supportedAttributeKinds.indexOf(name)
        return bitPosition >= 0 && (attributesBitmap and (1 shl bitPosition)) != 0
    }

    fun attributesAsMap(provider: CoreDependencyProvider): Map<String, Boolean> =
        provider.supportedAttributeKinds.withIndex().associate { (i, name) ->
            name to ((attributesBitmap and (1 shl i)) != 0)
        }
}

interface DetailDependencyProvider {
    /** The vocabulary of detail-level relationship kinds this provider supports. */
    val supportedRelationshipKinds: Set<String>

    /**
     * Returns Cypher for detail-level edges of the given kind, between the
     * given source and target type ID sets. The query should return rows of:
     * (sourceEntityId, sourceEntityKind, targetEntityId, targetEntityKind, location)
     */
    fun detailEdgeQuery(
        relationship: String,
        fromTypeIds: List<Long>,
        toTypeIds: List<Long>
    ): String

    /** Query for fetching full method details. */
    fun methodDetailsQuery(methodId: Long): String

    /** Query for fetching full field details. */
    fun fieldDetailsQuery(fieldId: Long): String
}
```

The `MappingProvider` is the top-level interface that bundles the three sub-providers:

```kotlin
interface MappingProvider {
    val hierarchy: HierarchyProvider
    val coreDependency: CoreDependencyProvider
    val detailDependency: DetailDependencyProvider

    /** Identifier for the scanner this provider represents. */
    val scannerId: String
}
```

The model layer takes a `MappingProvider` and uses it to populate itself at load time and to handle on-demand queries during operation. The model layer doesn't care which `MappingProvider` it has — only that it conforms to the interface.

## How scanner-specific vocabularies are handled

A key consequence of the provider model: the vocabulary of node kinds, edge attributes, and relationship kinds is scanner-driven, not hardcoded in Hierograph.

**Node kinds:** the hierarchy provider returns nodes with their kind strings (`java.module`, `java.method`, etc.). Hierograph doesn't validate against a hardcoded list; it accepts whatever kinds the provider produces. The namespace convention (`java.*`, `python.*`) is a convention scanners follow, but Hierograph treats kinds as opaque strings.

**Type-level edge attributes:** the core dependency provider declares its `supportedAttributeKinds` set. The Java provider declares `is_extends`, `is_implements`, `is_annotated_by`, `is_depends_on_other`. A Python provider would declare its own set, possibly including `is_decorated_by` instead of `is_annotated_by`, and might add `is_imports` as a distinct attribute. The set is scanner-specific.

**Detail-level relationship kinds:** the detail dependency provider declares its `supportedRelationshipKinds` set. When a tool receives a `relationship` parameter, it validates against the provider's declared set. If the parameter isn't in the set, the error response includes the supported set so the LLM learns what's available.

**The `graph_overview` tool surfaces all three vocabularies.** Its response includes the node kinds present in the loaded data, the type-level edge attributes the active provider supports, and the detail-level relationship kinds it supports. The LLM learns the vocabulary at session start, regardless of which scanner is in use.

This makes Hierograph genuinely scanner-agnostic. The Java vocabulary documented in the tool surface proposal is what the jQAssistant provider supports; other scanners support what they support, and the API adapts.

## What the model layer needs from providers

The model layer's contract with providers is narrow:

**At load time:**
- The hierarchy provider returns the structural skeleton
- The core dependency provider returns the type-level edges
- Both are bulk operations, executed once, producing the in-memory state

**At query time:**
- The lazy materialization cache fetches node properties via simple ID-based queries (the hierarchy provider could expose this, or it could be a separate small interface)
- The detail dependency provider fetches detail-level edges or entity details on demand
- These are point queries, executed as needed, results cached where appropriate

The model layer never composes complex queries itself. The complexity of Cypher generation lives in the provider; the model just asks "give me X" and consumes the result.

## How a new scanner gets added

The intended workflow for adding support for a new scanner:

1. **Implement `HierarchyProvider`** for the new scanner. Define the bulk queries that produce the hierarchy.

2. **Implement `CoreDependencyProvider`** for the new scanner. Define the bulk query that produces the type-level dependency edges. (For non-Java languages, "type" might map to "class," "module," "interface," or whatever the language calls its top-level structural unit.)

3. **Implement `DetailDependencyProvider`** for the new scanner. Define:
   - The set of detail-level relationship kinds the scanner supports
   - A query for each kind
   - Queries for the entity-detail tools

4. **Implement `MappingProvider`** that bundles the three sub-providers and identifies the scanner.

5. **Configure Hierograph to use the new provider.** Either via dependency injection (Spring profile selection) or a configuration setting.

6. **Verify behavior on a representative codebase.** The MCP tools, the model layer, and the in-memory graph should all work without modification. Only the provider changes.

No model layer changes. No tool layer changes. The change is contained to the provider module.

## What this enables

Several capabilities flow from the three-provider architecture:

**Multiple languages.** Python, TypeScript, Rust, C#, Go — anything where the structural concepts can be modeled as a hierarchy with type-level dependencies can be supported by adding a provider.

**Multiple scanners for the same language.** Java has multiple analysis tools (jQAssistant, Sourceforge, custom static analyzers). If a user wants to use a different Java scanner, they implement a `MappingProvider` against that scanner's schema.

**Non-Neo4j backends.** The provider interface defines what data is needed, not how it's stored. A provider could theoretically read from a SQL database, a JSON file, or any other source. The Cypher-string-returning shape of the current interface assumes Neo4j; a more abstract interface could decouple this further if needed.

**Testing without external dependencies.** A test `MappingProvider` that returns synthetic data lets tool and model layers be tested in isolation. No Neo4j, no jQAssistant — just deterministic test fixtures.

**Lighter-weight deployment for some use cases.** If a use case only needs the hierarchy without dependencies, a minimal provider that returns empty `CoreDependencyProvider` results works. Hierograph still functions; some tools return empty results, but nothing breaks.

## Boundaries: what stays in Hierograph regardless of scanner

A few things are inherent to Hierograph and don't move into providers:

- **The hierarchical model abstraction.** The notion of modules containing packages containing types containing members is Hierograph's data model, not a scanner-specific concept. Scanners adapt to it; they don't redefine it.

- **Aggregation algorithms.** How weights roll up, how kind flags are combined, how reachability is computed — these are Hierograph's algorithms, identical regardless of scanner.

- **MCP tool definitions and behaviors.** Tool parameters, response shapes, cursor protocols, error conventions — all scanner-agnostic.

- **The pagination protocol.** Page sizes, cursor format, iteration order conventions — defined by Hierograph, not by providers.

- **The NodeRef shape.** Minimal vs. enriched, the kind-aware metadata fields — Hierograph's contract with the LLM, not the scanner's concern.

The provider is *adapter code*. It translates between Hierograph's internal abstractions and the scanner's external reality. It doesn't introduce new abstractions of its own.

## What to be careful about

A few design temptations worth resisting:

**Don't over-abstract the provider interface.** Design for the scanners you actually intend to support, not for hypothetical maximum flexibility. If only jQAssistant is real today, the interface should fit jQAssistant cleanly while leaving room for the next 1-2 anticipated scanners. Adding flexibility later is easier than removing it.

**Don't push too much logic into providers.** Providers should translate data, not make decisions. If a provider starts containing logic about how to aggregate dependencies or when to recurse — that logic belongs in the model layer. The provider just fetches what the model asks for.

**Don't let providers know about each other.** The three sub-providers (hierarchy, core dependency, detail dependency) should be independent. If they need to share data — e.g., the detail dependency provider needs to know about a node loaded by the hierarchy provider — that coordination happens through the model layer, not directly between providers.

**Don't tie the provider interface to Cypher.** Currently the interfaces return Cypher strings, which assumes Neo4j. This is fine for v1 but worth noting as a coupling. A more abstract interface (returning a `Query` object that the model layer executes via an injected executor) would decouple this. Probably premature now; revisit if a non-Neo4j backend becomes interesting.

**Don't expose provider details to the LLM.** The fact that there's a `MappingProvider` is an implementation detail. The LLM sees only the MCP tools. Providers don't appear in tool descriptions, error messages, or `graph_overview` output — except indirectly through the scanner-specific vocabularies they declare.

## Migration considerations

The detail dependency provider is the missing piece. Bringing it in is a refactor, not a redesign:

1. **Identify the detail-level queries currently embedded in tool implementations.** They likely live in the implementations of `outgoing_dependencies` / `incoming_dependencies` at `detail_level: "detail"`, and in `method_details` / `field_details`.

2. **Move each query into a `JqAssistantDetailDependencyProvider`.** The provider class is a container for these queries, with methods that produce them on demand.

3. **Modify the tool implementations to call the provider.** They go from "embed a Cypher query" to "call `provider.detailDependency.detailEdgeQuery(...)`." External behavior unchanged.

4. **Add `supportedRelationshipKinds` to the provider and use it for parameter validation.** The hardcoded list of valid relationship kinds in the tool implementations becomes a reference to the provider's declared set.

5. **Update `graph_overview` to surface the active vocabulary from the provider.** Same data, sourced differently.

6. **Run the existing tests.** Everything should pass. The refactor is internal; nothing external changes.

7. **Optionally: write a test `MappingProvider`** that returns synthetic data, and rewrite some integration tests as unit tests against it.

This is a few-days refactor at most. The architectural payoff is significant: the three-provider model becomes complete and self-consistent.

## A summary diagram

The final architecture, with all three providers in place:

```
        ┌──────────────────────┐
        │  MCP Tool Layer      │
        │  (scanner-agnostic)  │
        └──────────┬───────────┘
                   │ navigate(), aggregate(),
                   │ detailEdges(), ...
                   ↓
        ┌──────────────────────┐
        │  Model Layer         │
        │  (in-memory graph,   │
        │   scanner-agnostic)  │
        └──────────┬───────────┘
                   │ provider.hierarchy(),
                   │ provider.coreDependency(),
                   │ provider.detailDependency()
                   ↓
        ┌──────────────────────┐
        │  MappingProvider     │
        │  (scanner-specific)  │
        ├──────────────────────┤
        │ ├─ HierarchyProvider │
        │ ├─ CoreDependency-   │
        │ │   Provider         │
        │ └─ DetailDependency- │
        │     Provider         │
        └──────────┬───────────┘
                   │ Cypher
                   ↓
              ┌────────┐
              │ Neo4j  │
              └────────┘
```

One vertical slice per layer. Each layer's interface to the next is small and well-defined. New scanners replace the bottom layer without touching the upper layers.

## Closing observation

The provider model isn't a radical change to Hierograph. The pattern is half-built already — hierarchy and core dependency queries are already provider-driven. What this document describes is *completing* the pattern by extracting detail-level queries into the same abstraction.

The payoff is real: a coherent three-layer architecture where scanner-specific knowledge lives in one well-defined place, and the rest of Hierograph is scanner-agnostic. The marketing story ("works with any scanner that provides a graph model") becomes architecturally honest rather than aspirational.

It's the right next step, and it's a small step, because the foundation is already there.
