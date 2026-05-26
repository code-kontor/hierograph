# Cartograph: Inherited Edges in `detail_dependencies`

## Summary

When using `detail_dependencies`, the `total_edges` count and the `by_source_nodes` aggregation in the parent scope can report different numbers for the same source/target pair. This is not a bug — it is caused by **inherited edges** being included in `total_edges` but not in `by_source_nodes`.

## How inheritance traversal works

The `detail_dependencies` tool always traverses `EXTENDS` and `IMPLEMENTS` relationships on both sides. When a type in the from-subtree inherits from an ancestor **outside** the from-subtree, edges declared on that ancestor are included in the result. The `from_parent` field on each edge reports the **actual declaring type**, which may be an ancestor outside the from-subtree.

From the tool documentation:

> *"An edge whose source method/field is declared on an ancestor of a type in the from-subtree is included. `from_parent` therefore reports the actual declaring type, which may be an ancestor outside the from-subtree when the entity is inherited. To restrict the result to physically-declared edges, filter edges where `from_parent` is in the from-subtree."*

## Example: Analysis → Rule

### Query 1: Project-level scope

```
detail_dependencies(fromId=4238 [Analysis project], toId=4246 [Rule project], limit=1)
```

The `by_source_nodes` in the summary reports:

| Source artifact node | aggregated_weight |
|---------------------|------------------:|
| `analysis-2.10.0-SNAPSHOT.jar` (node 3522) | **360** |
| `analysis-2.10.0-SNAPSHOT-tests.test-jar` (node 30868) | 379 |

### Query 2: Artifact-level scope

```
detail_dependencies(fromId=3522 [analysis.jar], toId=3526 [rule.jar], limit=1)
```

The summary reports:

```json
{
  "total_edges": 464,
  "by_source_type": [
    { "type": "AnalyzerRuleVisitor",                "edge_count": 129 },
    { "type": "AnalyzerRuleVisitorAuditDecorator",  "edge_count": 98 },
    { "type": "AbstractRuleVisitor",                "edge_count": 56 },
    { "type": "AnalyzerContextImpl",                "edge_count": 40 },
    { "type": "RuleVisitor",                        "edge_count": 32 },
    { "type": "ScriptRuleInterpreterPlugin",        "edge_count": 16 },
    { "type": "AggregationVerificationStrategy",    "edge_count": 15 },
    { "type": "AbstractCypherRuleInterpreterPlugin", "edge_count": 12 },
    { "type": "CypherRuleInterpreterPlugin",        "edge_count": 10 },
    { "type": "RuleInterpreterPlugin",              "edge_count": 8 }
  ],
  "others_count": 14
}
```

### The discrepancy: 360 vs 464

The difference of **104 edges** comes from inherited edges:

| Source type | Edges | Physically in analysis.jar? |
|------------|------:|:---------------------------:|
| `AnalyzerRuleVisitor` | 129 | yes |
| `AnalyzerRuleVisitorAuditDecorator` | 98 | yes |
| **`AbstractRuleVisitor`** | **56** | **no — declared in rule.jar** |
| `AnalyzerContextImpl` | 40 | yes |
| **`RuleVisitor`** | **32** | **no — declared in rule.jar** |
| `ScriptRuleInterpreterPlugin` | 16 | yes |
| *(others)* | 93 | mixed |

`AbstractRuleVisitor` and `RuleVisitor` are physically declared in **rule.jar**, not analysis.jar. However, because `AnalyzerRuleVisitor` extends `AbstractRuleVisitor` and implements `RuleVisitor`, their edges are pulled in via inheritance traversal.

- **360** (`by_source_nodes` in query 1) = edges where `from_parent` is physically inside `analysis.jar`
- **464** (`total_edges` in query 2) = 360 own edges + 104 inherited edges from ancestors in `rule.jar`

### Inheritance chain

```
RuleVisitor (interface, in rule.jar)
    ^
    | implements
    |
AbstractRuleVisitor (abstract class, in rule.jar)
    ^
    | extends
    |
AnalyzerRuleVisitor (concrete class, in analysis.jar)
```

Edges declared on `RuleVisitor` (32) and `AbstractRuleVisitor` (56) are attributed to `AnalyzerRuleVisitor` via inheritance, inflating the `total_edges` count when querying from the `analysis.jar` scope.

## How to get consistent numbers

To restrict `detail_dependencies` results to **physically-declared edges only**, filter the returned edges where `from_parent` is a node within the from-subtree. Use `list_descendants(fromId)` to obtain the set of valid `from_parent` IDs and exclude any edge whose `from_parent` falls outside that set.

## Implications for analysis

- When comparing `by_source_nodes` weights from a parent-scope query with `total_edges` from a child-scope query, expect inherited edges to inflate the child-scope total.
- For architectural coupling analysis (e.g., DSM), the `by_source_nodes` / `by_target_nodes` weights (which exclude inherited edges) are typically the more useful metric, as they reflect **direct, physically-declared coupling**.
- Inherited edges are still valuable for understanding the **effective API surface** a type depends on, even if the dependency is mediated through a supertype.
