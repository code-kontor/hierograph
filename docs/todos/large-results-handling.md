# Handling Large Results from detail_dependencies

## Problem

When `mcp__hierograph__detail_dependencies` is called with a high `limit` (e.g., 300+), the result can exceed the context window limit (~90KB+ for 300 edges). When this happens:

1. The result is saved to a temporary file instead of being returned inline
2. The main context doesn't have the data needed to build the graph
3. Attempting to use an Agent to process the file adds latency and may be rejected by the user

## Root Cause

Each edge in the `detail_dependencies` response carries:
- `from` / `to` (method/field IDs)
- `from_parent` / `to_parent` (class IDs)
- `relationship` kind
- `location` (file path + line number)

Plus the `nodes` map resolves every referenced ID to `{ name, qualified_name, kind }`. With 300+ edges referencing ~100+ unique nodes, the JSON easily reaches 80-90KB.

## Solutions (ordered by preference)

### Solution 1: jq aggregation on the saved file (immediate, no server change)

When total edges exceed ~100, don't try to fit them in context. Instead:

1. Call `detail_dependencies` with full limit — let it spill to file
2. Use `jq` via Bash to aggregate the edges by `(from_parent, to_parent)` and resolve names
3. The jq output is small (just the aggregated tuples) and fits in context

**jq command template:**

```bash
# Extract the JSON payload from the tool-results wrapper, then aggregate
cat <FILE> | jq -r '.[0].text' | jq '
  .edges
  | group_by([.from_parent, .to_parent])
  | map({
      from_parent: .[0].from_parent,
      to_parent: .[0].to_parent,
      count: length
    })
  | sort_by(-.count)
' > /tmp/aggregated-edges.json

# Resolve names separately
cat <FILE> | jq -r '.[0].text' | jq '.nodes' > /tmp/nodes-map.json
```

Then read the small aggregated file and the nodes map to build the `{{EDGES}}` array.

**Pros:** Works today, no server changes needed, deterministic
**Cons:** Two-step process, relies on jq being available (it usually is on macOS/Linux)

### Solution 2: Two-pass approach with limit cap (immediate, skill-level fix)

Never fetch more than 100 edges into context. Instead:

1. First call: `limit: 1` → get the summary (`by_source_type`, `by_source_nodes`, `by_target_nodes`)
2. If `total_edges <= 100`: fetch all edges with `limit: <total>`, aggregate in context
3. If `total_edges > 100`: use Solution 1 (jq on file)

This avoids the large-result problem in most cases and falls back gracefully.

**Pros:** Simple decision logic, no server changes
**Cons:** Still needs the jq fallback for large graphs

### Solution 3: Server-side aggregation parameter (Hierograph enhancement)

Add an `aggregate` or `groupBy` parameter to `detail_dependencies`:

```
detail_dependencies(fromId, toId, relationship, aggregate: "by_parent")
```

Returns:
```json
{
  "aggregated_edges": [
    { "from_parent": 12345, "to_parent": 67890, "count": 15 },
    ...
  ],
  "nodes": { ... only referenced parents ... }
}
```

This is the cleanest solution — the server does the aggregation, the result is always small (bounded by number of unique class pairs, typically <50 even for large modules), and no post-processing is needed.

**Pros:** Cleanest, fastest, always fits in context, no jq dependency
**Cons:** Requires a Hierograph server change

### Solution 4: Pagination (Hierograph enhancement, alternative)

Add `offset` support to `detail_dependencies`:

```
detail_dependencies(fromId, toId, relationship, limit: 100, offset: 0)
detail_dependencies(fromId, toId, relationship, limit: 100, offset: 100)
```

Fetch in pages of 100, aggregate incrementally.

**Pros:** General-purpose, works for all use cases
**Cons:** Multiple round-trips, more complex client logic, still needs aggregation

## Recommendation

**Short term:** Update the `dep-graph` SKILL.md to implement Solution 2 (limit cap + jq fallback). This works today without any server changes.

**Medium term:** Implement Solution 3 in Hierograph. This eliminates the problem entirely and makes the visualization skill trivial.

## Decision Threshold

Based on testing:
- ~100 edges ≈ 25-30KB → fits comfortably in context
- ~200 edges ≈ 50-60KB → borderline, may work
- ~300+ edges ≈ 80KB+ → will spill to file

Use **100 edges** as the safe threshold for in-context processing.

## Skill Update Required

The `dep-graph/SKILL.md` should be updated to include:

```markdown
### Handling large edge sets (>100 edges)

If the summary shows `total_edges > 100`:

1. Call `detail_dependencies` with the full limit (it will save to a file)
2. Use jq to aggregate:
   ```bash
   cat <RESULT_FILE> | jq -r '.[0].text' | jq '
     .edges | group_by([.from_parent, .to_parent])
     | map({fp: .[0].from_parent, tp: .[0].to_parent, w: length})
     | sort_by(-.w)
   '
   ```
3. Read the nodes map: `cat <RESULT_FILE> | jq -r '.[0].text' | jq '.nodes'`
4. Combine into the `{{EDGES}}` format using resolved names
```
