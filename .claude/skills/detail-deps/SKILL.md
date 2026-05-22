# Detail Dependencies Analysis

Analyze the detail-level dependencies between two modules using the cartograph MCP server.

## Instructions

You will be given two node IDs: a source module and a target module. Follow these steps precisely:

### Step 1: Fetch the summary

Call `mcp__cartograph__detail_dependencies` with `limit: 1` to retrieve:
- The full **summary** (total edges, by_relationship, by_source_type, by_source_nodes, by_target_nodes) with correct counts
- The **resolved node names** for all types referenced in the summary (the nodes map)

This avoids flooding the context with hundreds of edges.

### Step 2: Present the summary

Display the results in two tables:

**By relationship kind** — show each relationship kind, its count, and percentage of total edges. Sort by count descending.

**By source type** — map each type ID from `by_source_type` to its resolved name from the `nodes` map. Show the class name, its qualified name, edge count, and whether it's a test class. Include the `others_count` as a final row.

Also note:
- Total edge count and whether results were truncated
- What percentage of edges come from test classes vs production code
- If `by_source_nodes` and `by_target_nodes` are present, show them as additional tables

### Step 3: Warn about inherited edges discrepancy

The `total_edges` count from `detail_dependencies` includes **inherited edges** — edges declared on ancestor types (via EXTENDS/IMPLEMENTS) that are outside the from-subtree. This means:

- When drilling from a parent scope into a child scope (e.g., from a Project into an Artifact), the child scope's `total_edges` may be **higher** than the `by_source_nodes` weight reported in the parent scope.
- The `by_source_nodes` weight from the parent scope counts only **physically-declared edges** (where `from_parent` is inside the node's subtree).
- The `total_edges` in the child scope includes those plus edges inherited from ancestors outside the subtree.

If you observe this discrepancy, explain it to the user. Identify which types in `by_source_type` are **not physically inside** the from-scope (look at their qualified names — if they belong to the target module's package, they are inherited). Report the count of inherited vs own edges.

Example: When querying `analysis.jar → rule.jar`, the types `AbstractRuleVisitor` and `RuleVisitor` (from `com.buschmais.jqassistant.core.rule.api.executor`) are physically in rule.jar but appear as source types because `AnalyzerRuleVisitor` in analysis.jar extends/implements them.

### Step 4: Ask how to proceed

Present the user with these options:

1. **Drill into a specific relationship kind** — e.g., only `calls`, `parameter_type`, `overrides`, `throws`, `reads_field`, `returns`, `has_type`, `writes_field`, `annotated_by`
2. **Drill into a specific source type** — show all edges originating from one particular class
3. **Fetch more edges** — increase the limit to see the actual edges (warn about context size)
4. **Done** — end the analysis

Wait for the user's choice before proceeding.

### When drilling into a relationship kind

Call `mcp__cartograph__detail_dependencies` again with the same `fromId`/`toId` but add the `relationship` parameter. Use a reasonable limit (e.g., 50) and present the edges in a table showing: source method/field, target method/field, and source location (file + line number).

### When drilling into a source type

Use `mcp__cartograph__detail_dependencies` with a reasonable limit (e.g., 50). Filter or highlight edges where `from_parent` matches the selected source type ID. Present as a table.

## Arguments

- `$ARGUMENTS` — Two node IDs separated by a space: `<fromId> <toId>`. Use the cartograph tools (e.g., `find_node`, `list_children`, `describe_graph`) to look up node IDs if the user provides module names instead of IDs.
