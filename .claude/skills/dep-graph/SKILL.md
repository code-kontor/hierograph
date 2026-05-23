# Dependency Graph Visualization

Visualize call dependencies between two modules as an interactive HTML graph. Use when the user asks to draw, visualize, graph, or chart dependencies — especially after drilling into a dependency edge with the `/detail-deps` skill.

## Instructions

### When to use

Use this skill when the user asks to visualize or draw a dependency graph. It works best after you already have detail-level dependency data from `mcp__cartograph__detail_dependencies` with `relationship: "calls"`. If you don't have that data yet, fetch it first.

### Step 1: Get the call edges

If not already available, call `mcp__cartograph__detail_dependencies` with:
- `fromId` / `toId` set to the two modules
- `relationship: "calls"`
- `limit: 1` first to check `summary.total_edges`

**Then decide based on edge count:**

- **total_edges <= 100:** Fetch all edges with `limit: <total>`. Aggregate in context (Step 2a).
- **total_edges > 100:** Fetch with full limit (it will spill to a file). Use jq to aggregate (Step 2b).

### Step 2a: Aggregate in context (<=100 edges)

Group the raw edges by `(from_parent, to_parent)` pair and count occurrences. The `from_parent` is the source class, `to_parent` is the target class. Resolve their names from the `nodes` map.

### Step 2b: Aggregate via jq (>100 edges)

When the result is saved to a file (too large for context), use Bash with jq:

```bash
# Aggregate edges by (from_parent, to_parent) and get counts
cat <RESULT_FILE> | jq -r '.[0].text' | jq '
  .edges | group_by([.from_parent, .to_parent])
  | map({fp: .[0].from_parent, tp: .[0].to_parent, w: length})
  | sort_by(-.w)
' > /tmp/dep-graph-aggregated.json

# Extract the nodes map for name resolution
cat <RESULT_FILE> | jq -r '.[0].text' | jq '.nodes' > /tmp/dep-graph-nodes.json
```

Read both files. Use the nodes map to resolve IDs to short class names, then build the `{{EDGES}}` array.

Use short class names (not fully qualified) for readability. If two classes share a short name, disambiguate with the package prefix.

Mark inherited sources (classes whose `qualified_name` belongs to the **target** module's package) by appending `*` to their name.

### Step 3: Generate the HTML

Read the template at `.claude/skills/dep-graph/call-deps-template.html`.

Replace these placeholders:

| Placeholder | Value |
|---|---|
| `{{TITLE}}` | e.g., `analysis.impl → Core Rule — calls only` |
| `{{SUBTITLE}}` | e.g., `132 call edges · hover over a source or target to highlight its connections` |
| `{{EDGES}}` | JSON array of `{ s: 'SourceClass', t: 'TargetClass', w: count }` objects |

### Step 4: Write and open the file

Write the generated HTML to `.claude/<from>-to-<to>-calls.html` and open it with:

```bash
open .claude/<from>-to-<to>-calls.html
```

### Template features

- **Left column:** source classes, sized by total outgoing calls, sorted by weight
- **Right column:** target types, sized by incoming calls, sorted by weight
- **Edges:** bezier curves, width proportional to call count
- **Colors:** orange = own source, green = inherited source, blue = target
- **Hover:** highlights connected edges and shows per-target/per-source breakdown in a tooltip
- **Dark theme:** GitHub-dark inspired color scheme

### Tips

- Keep source names short but recognizable (truncate or abbreviate long class names)
- For very dense graphs (>30 source or target nodes), consider filtering to the top N by weight
- The template works for any relationship kind, not just `calls` — adjust the title accordingly
