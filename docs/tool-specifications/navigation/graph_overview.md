# `graph_overview`

**Category:** Discovery and navigation
**Result-size class:** Input-bounded (single fixed-shape response, no pagination needed)

## Purpose

Returns a structural overview of the loaded codebase: statistics, the kind and relationship vocabularies, top-level module structure, and a description of the zoom-level model for dependency analysis.

This is the orientation tool. The LLM calls it once at the start of a session to learn what's in the codebase, what vocabulary the other tools use, and how the dependency analysis tools relate to each other. For familiar codebases where the LLM already knows the structure, it can be skipped.

Renamed from `describe_graph` — the new name better reflects what the tool returns (a structural overview, not a description of the graph engine).

## Signature

```
graph_overview()
```

No parameters. The tool always returns the overview for the entire loaded graph.

The current implementation accepts an optional `scopeId` to describe a subtree. This is removed: scoped exploration is the job of `list_children` and `list_descendants`. `graph_overview` is strictly a whole-graph orientation tool.

## Response shape

```json
{
  "stats": {
    "total_nodes": 112847,
    "nodes_by_kind": {
      "java.module": 12,
      "java.package": 487,
      "java.class": 3291,
      "java.interface": 891,
      "java.enum": 204,
      "java.record": 18,
      "java.annotation": 97,
      "java.method": 28412,
      "java.field": 9103
    },
    "total_type_level_edges": 47291,
    "type_level_edges_by_kind": {
      "depends_on": 38102,
      "extends": 3291,
      "implements": 4102,
      "annotated_by": 1796
    }
  },
  "kinds": {
    "structural": [
      { "kind": "java.module", "description": "Build module (Maven/Gradle)" },
      { "kind": "java.package", "description": "Java package; contains sub-packages and types" },
      { "kind": "java.class", "description": "Class" },
      { "kind": "java.interface", "description": "Interface" },
      { "kind": "java.enum", "description": "Enum type" },
      { "kind": "java.record", "description": "Record (Java 14+)" },
      { "kind": "java.annotation", "description": "Annotation type" },
      { "kind": "java.method", "description": "Method (includes constructors)" },
      { "kind": "java.field", "description": "Field" }
    ],
    "group_aliases": {
      "types": ["java.class", "java.interface", "java.enum", "java.record", "java.annotation"],
      "members": ["java.method", "java.field"],
      "packages": ["java.package"]
    }
  },
  "relationships": {
    "type_level": [
      { "kind": "depends_on", "description": "Generic dependency (always present when any detail-level dependency exists)" },
      { "kind": "extends", "description": "Source type extends target type" },
      { "kind": "implements", "description": "Source type implements target interface" },
      { "kind": "annotated_by", "description": "Source type is annotated by target annotation type" }
    ],
    "detail_level": [
      { "kind": "throws", "source": "method", "description": "Method declares it throws this exception type" },
      { "kind": "calls", "source": "method", "description": "Method invokes a method" },
      { "kind": "returns", "source": "method", "description": "Method's return type" },
      { "kind": "parameter_type", "source": "method", "description": "Method has a parameter of this type" },
      { "kind": "reads_field", "source": "method", "description": "Method reads a field" },
      { "kind": "writes_field", "source": "method", "description": "Method writes a field" },
      { "kind": "overrides", "source": "method", "description": "Method overrides another method" },
      { "kind": "annotated_by", "source": "method/field", "description": "Entity has this annotation type" },
      { "kind": "parameter_annotated_by", "source": "method", "description": "Method has a parameter with this annotation type" },
      { "kind": "has_type", "source": "field", "description": "Field is of this type" },
      { "kind": "read_by", "source": "field", "description": "Field is read by this method" },
      { "kind": "written_by", "source": "field", "description": "Field is written by this method" }
    ]
  },
  "hierarchy": [
    {
      "node": {
        "id": 1001,
        "name": "elasticsearch-server",
        "qualified_name": "org.elasticsearch:elasticsearch-server",
        "kind": "java.module",
        "parent_id": null,
        "parent_kind": null
      },
      "child_count": 48,
      "descendant_type_count": 1203,
      "descendant_method_count": 12847,
      "outgoing_dep_count": 3291,
      "incoming_dep_count": 2187
    }
  ],
  "model": {
    "aggregation": "Aggregation is pairwise. Given any two subtrees, aggregated_dependencies computes one aggregated edge between them. Provide source_ids and target_ids as sets; the result includes one edge per (source, target) pair that has a dependency.",
    "levels": [
      {
        "name": "aggregated",
        "description": "Pairwise rollup of dependencies between subtrees, with weight and kinds",
        "tools": ["aggregated_dependencies", "pairwise_dependencies"]
      },
      {
        "name": "type",
        "description": "Type-to-type edges between two specific subtrees, fast (in-memory)",
        "tools": ["outgoing_dependencies", "incoming_dependencies"],
        "parameter": "detail_level=\"type\" (default)"
      },
      {
        "name": "detail",
        "description": "Method/field-level edges between two specific subtrees, queried on demand",
        "tools": ["outgoing_dependencies", "incoming_dependencies"],
        "parameter": "detail_level=\"detail\""
      }
    ]
  },
  "scan_metadata": {
    "scanner": "jqassistant",
    "scanned_at": "2026-05-22T14:30:00Z"
  }
}
```

### Response sections

**`stats`** — quantitative summary of the loaded graph. Node counts by kind, total type-level edge count, and edge counts by type-level edge kind. Gives the LLM a sense of scale before it starts navigating.

**`kinds`** — the vocabulary of node kinds present in the graph, with brief descriptions. Includes group aliases that other tools accept in `kind_filter` parameters. The LLM learns the valid kind values here and uses them in subsequent tool calls.

**`relationships`** — the vocabulary of relationship kinds, split into type-level (flags on aggregated edges) and detail-level (specific edge kinds queryable via `outgoing_dependencies`/`incoming_dependencies` with `detail_level: "detail"`). Each detail-level kind indicates whether the source is a method, field, or both.

**`hierarchy`** — the top-level modules with enriched metadata (child count, descendant type/method counts, dependency counts). This is the entry point for navigation — the LLM picks a module from here and drills down with `list_children` or `list_descendants`.

**`model`** — a brief description of the zoom-level model and how the dependency tools relate to each other. The LLM reads this once and learns the three-level drill-down pattern (aggregated → type → detail) and which tools operate at which level.

**`scan_metadata`** — identifies the scanner that produced the data and when the scan was run. Informational; not used by the LLM for tool selection, but useful for the user to know what they're looking at.

## Architecture

`graph_overview` assembles its response from two sources:

### In-memory model (most of the response)

The following are computed directly from the in-memory hierarchical graph:

- **`stats.total_nodes`** and **`stats.nodes_by_kind`** — counted from the in-memory node set
- **`stats.total_type_level_edges`** and **`stats.type_level_edges_by_kind`** — counted from the in-memory type-level dependency edges
- **`hierarchy`** — the top-level modules with their enriched metadata, all from the in-memory model
- **`model`** — static content, hardcoded in the tool

### Provider layer (vocabularies and metadata)

- **`kinds`** — the kind vocabulary is declared by the provider. The set of structural kinds and group aliases comes from the `MappingProvider`, not from hardcoded lists. This makes the response correct for any scanner.
- **`relationships`** — the detail-level relationship vocabulary comes from `DetailDependencyProvider.supportedRelationshipKinds`. The type-level edge kinds come from the in-memory model's edge kind flags.
- **`scan_metadata`** — scanner name and scan timestamp come from the provider.

This split means `graph_overview` doesn't issue any Neo4j queries at call time. Everything is either already in memory or statically declared by the provider. The response is assembled in microseconds.

### No `scopeId` parameter

The current `describe_graph` accepts an optional `scopeId` to describe a subtree. This is removed:

- Scoped exploration is the job of `list_children` (one level) and `list_descendants` (multi-level)
- A "scoped overview" mixes two concerns: orientation (what's the codebase?) and navigation (what's in this subtree?)
- Removing the parameter makes the tool zero-input, which simplifies the LLM's decision: "I'm new here" → `graph_overview()`, always

## Use cases

- **"I'm new here; what am I looking at?"** — `graph_overview()` at session start
- **"What kinds of nodes exist?"** — `graph_overview()`, read `kinds`
- **"What relationship types can I query?"** — `graph_overview()`, read `relationships`
- **"How big is this codebase?"** — `graph_overview()`, read `stats`
- **"What modules are there?"** — `graph_overview()`, read `hierarchy`

## LLM tool description

The `@Tool` description should communicate:

1. This is the whole-graph orientation tool — call it first when starting a new session
2. It returns statistics, vocabularies, top-level structure, and the zoom-level model
3. No parameters needed — always describes the full loaded graph
4. For known codebases, it can be skipped — the information is static for the lifetime of the loaded graph
