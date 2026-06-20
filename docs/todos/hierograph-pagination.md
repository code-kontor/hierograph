# Hierograph: Pagination and Cursors

This document specifies how Hierograph handles pagination on tools that can produce large responses. The goal is preventing context-window overflow in the calling LLM while keeping the design stateless — cursors must work across server restarts.

This is a focused design document. The tool surface proposal references this for the cursor protocol details; everything cursor-related lives here.

## The problem

Several Hierograph tools can produce responses with hundreds or thousands of result items. In practice, the LLM's context window is the binding constraint: a response with 750 detail-level dependencies fills up enough context to interfere with reasoning, even when each individual item is small.

The solution is pagination: return a manageable page size by default (200 items), with a cursor for retrieving additional pages when the LLM specifically needs more. This is observed user behavior, not theoretical — large responses do disrupt sessions.

## Design principles

Four principles drive the specific design:

**Stateless.** Cursors encode everything needed to resume. No server-side state, no caches that need to persist, no session affinity. The server can be restarted between issuing a cursor and using it, and the cursor still works (provided the underlying data is unchanged).

**Self-validating.** Cursors carry enough information to detect when they shouldn't be used — wrong tool, stale data, modified query parameters. Invalid cursors fail with clear errors rather than silently returning wrong results.

**Recoverable.** When a cursor fails, the error indicates the recovery path explicitly. The LLM never has to guess what went wrong or what to do.

**Opaque to the LLM.** The cursor is an arbitrary string from the LLM's perspective. It doesn't construct, parse, or manipulate cursors — it passes them through. The internal structure exists for the server's use, not the LLM's.

## Cursor structure

A cursor is a base64-URL-encoded JSON object with five fields:

```json
{
  "v": 1,
  "tool": "list_descendants",
  "qh": "a7f2c8d1...",
  "dh": "8f3e2c1a...",
  "offset": 200
}
```

**`v`** — cursor format version. Currently `1`. Lets the format evolve without breaking compatibility-mode handling; old cursors with a version the server no longer supports fail with a clear error.

**`tool`** — the name of the tool that issued this cursor. Lets the server detect when a cursor is being used on a different tool than it was issued for (an LLM bug that's worth flagging explicitly).

**`qh`** — query hash. A truncated SHA-256 hash (first 12 bytes, base64-encoded) of the request's query parameters. Lets the server detect when the LLM changes parameters between pages, which would produce incoherent results if silently ignored.

**`dh`** — data hash. An identifier for the snapshot of the underlying graph data when this cursor was issued. Lets the server detect when the underlying data has changed since the cursor was issued, which would invalidate the offset's meaning.

**`offset`** — the position in the result list to resume from. Zero-indexed integer.

The encoded form is approximately 200 bytes — the size of a typical URL parameter, fine for any transport.

## What gets paginated

Pagination applies only to tools whose result size isn't bounded by input parameters and can genuinely grow large. The principle: pagination is driven by response size, not by tool type or zoom level. A tool needs cursors if its result count can grow large even with reasonable inputs.

Paginated tools:

- **`list_descendants`** — traversal of large subtrees can return thousands of nodes
- **`outgoing_dependencies`** and **`incoming_dependencies`** at *both* detail levels — the response is bounded by data complexity, not input size, so both type-level and detail-level queries can produce large results. A query between two large modules can return hundreds or thousands of type-pair edges at `detail_level: "type"`, or method/field-level edges at `detail_level: "detail"`.
- **`affected_by`** — blast radius on a heavily-coupled type can include many affected types

Other tools don't need cursors:

- **Aggregated tools** (`aggregated_dependencies`, `pairwise_dependencies`) — result size is bounded by the input cross product. The LLM controls result size through input size.
- **Entity-detail tools** (`method_details`, `field_details`) — return a single entity's data, bounded by inherent size.
- **Navigation tools at small scope** (`list_children`, `find_node`) — typically produce small results. `list_children` could in principle benefit from pagination on enormous types, but in practice the limit parameter handles this without needing cursor protocol overhead.
- **Path tools** (`find_dependency_path`) — bounded by the `max_paths` parameter.

The distinction: tools whose result size is bounded by input parameters use `limit` for safety against degenerate inputs but don't need cursors. Tools whose result size is bounded by underlying data complexity need cursors because the LLM can't control result size through input shaping.

## Iteration order

For cursors to work, each paginated tool must have a deterministic iteration order over its results. Same input data + same query parameters must produce the same result sequence every time, regardless of when the query runs.

### `list_descendants`

Depth-first pre-order traversal of the hierarchy from the input node. Children at each level visited in stable order: by qualified name (alphabetical). Methods and fields within a type are interleaved in the order jQAssistant captures them; if that order isn't stable, sort by name.

### `outgoing_dependencies` and `incoming_dependencies` (type level)

Type-level edges sorted by tuple `(source_type_qualified_name, target_type_qualified_name)`. Source type first, then target — produces a result where all edges from the same source type appear contiguously.

### `outgoing_dependencies` and `incoming_dependencies` (detail level)

Edges sorted by tuple `(source_type_qualified_name, source_entity_name, target_qualified_name, relationship)`. This produces a stable order where edges from the same source type are grouped, edges with the same source entity are sub-grouped, and within a single source/target pair the relationship kinds are ordered alphabetically.

### `affected_by`

Affected types sorted by `(distance_ascending, qualified_name_alphabetical)`. The closest-affected types appear first, with ties broken alphabetically. This puts the highest-priority results (closest coupling) at the start of the result set.

## Response shape for paginated tools

A paginated tool's response includes a `summary` block and an optional `next_cursor`:

```json
{
  "results": [ ... up to limit items ... ],
  "summary": {
    "total": 750,
    "returned": 200,
    "truncated": true
  },
  "next_cursor": "eyJ2IjoxLCJ0b29sIjoibGlzdF9kZX..."
}
```

**`results`** — the page of items, up to `limit` in size.

**`summary.total`** — the true count of all matching results, regardless of how many fit in this page. The LLM sees the actual size of the result set, not just the page.

**`summary.returned`** — the number of items in this page.

**`summary.truncated`** — `true` if `total > returned`, `false` otherwise. Convenience flag; `next_cursor` presence is the authoritative signal.

**`next_cursor`** — present *if and only if* more results exist after this page. The LLM passes this as the `cursor` parameter on the next call to retrieve the next page. When the last page is returned, this field is omitted (not set to `null`) — absence is the unambiguous "you have everything" signal.

## Request parameters

Paginated tools accept two parameters relevant to pagination:

**`limit`** — maximum number of items per page. Per-tool defaults; see the next section. The LLM can specify smaller values for sampling or larger values when it knows the result fits, up to the server-side cap.

**`cursor`** — opaque cursor string from a previous response's `next_cursor`. When present, the tool returns the next page in the iteration. When absent, the tool starts from the beginning.

The `cursor` parameter is mutually informative with the other query parameters. When a cursor is supplied, the server validates that the request's other parameters match what the cursor was issued for (via the query hash). Mismatches are errors, not silent re-interpretations.

The `limit` parameter is *not* covered by the query hash. The LLM can legitimately use different page sizes for different pages of the same query — for example, smaller pages while exploring, then a larger page once confident.

## Page-size defaults and limits

Page-size defaults are calibrated against the constraint that matters in practice: **the calling client's MCP response size limit**, not Hierograph's own capacity to compute or serialize results.

For Claude Code (the primary target client) as of mid-2026:

- **Warning threshold:** 10,000 tokens. Claude Code displays a warning when an MCP tool's response exceeds this.
- **Default hard limit:** 25,000 tokens. Responses above this are rejected unless the user has raised `MAX_MCP_OUTPUT_TOKENS`.
- **Per-tool override:** tools that set the `anthropic/maxResultSizeChars` annotation declare their own limit. Hierograph should set this annotation per paginated tool to match the configured server-side cap.

The page-size defaults aim for the sweet spot of "comfortably under the 10K-token warning threshold," leaving headroom for the response's `summary` block, the `next_cursor` field, and JSON overhead. Server-side caps prevent any user-supplied `limit` value from producing responses above the 25K-token hard limit.

Different tools have different per-item response sizes, so the defaults vary by tool:

### `list_descendants`

Items are enriched NodeRefs (~250 bytes JSON each).

- Default `limit`: **150** (~37 KB, ~9K tokens — under warning threshold)
- Server-side cap: 500 (~125 KB, ~31K tokens — exceeds default Claude Code limit, requires user to raise `MAX_MCP_OUTPUT_TOKENS`)

### `outgoing_dependencies` / `incoming_dependencies` at `detail_level: "type"`

Items are type-to-type edges with source/target NodeRefs, weight, type_pair_count, and structured attributes (~350 bytes JSON each).

- Default `limit`: **100** (~35 KB, ~9K tokens — under warning threshold)
- Server-side cap: 400 (~140 KB, ~35K tokens — exceeds default limit, requires user raise)

### `outgoing_dependencies` / `incoming_dependencies` at `detail_level: "detail"`

Items are method/field-level edges with source location (file path + line number), source/target NodeRefs, and relationship kind (~550 bytes JSON each — larger because of the location data).

- Default `limit`: **80** (~44 KB, ~11K tokens — at warning threshold)
- Server-side cap: 250 (~138 KB, ~34K tokens — exceeds default limit, requires user raise)

The detail-level case is the tightest — 80 edges is significantly fewer than 200, but the per-item payload is substantially larger.

### `affected_by`

Items are affected type NodeRefs with `distance`, `source_count`, and a `via` representative path (~450 bytes JSON each).

- Default `limit`: **100** (~45 KB, ~11K tokens — at warning threshold)
- Server-side cap: 350 (~158 KB, ~39K tokens — exceeds default limit, requires user raise)

### Configurability notes

These defaults assume Claude Code's default token limits. Users who raise `MAX_MCP_OUTPUT_TOKENS` (e.g., to 100K tokens) can request larger pages by passing higher `limit` values, up to the server-side cap.

The server-side caps are deliberately above Claude Code's default 25K-token limit. This means a user with the default configuration will see truncation or warnings if they request `limit` values near the cap. The cap exists to prevent runaway responses, not to enforce the client-side limit — that's the client's job.

The `anthropic/maxResultSizeChars` annotation should be set per tool to match the *server-side cap converted to characters* (roughly cap × per-item-size). This tells Claude Code "this tool's responses won't exceed X characters," letting the client size its budget appropriately.

### Why the defaults are conservative

Targeting "comfortably under 10K tokens" rather than "close to 25K tokens" gives several practical benefits:

- No user-visible warnings during normal use
- Headroom for outlier responses (a single page with unusually large items)
- Room for the response's framing data (summary block, cursor, JSON overhead)
- Better LLM behavior — smaller responses are easier for the LLM to process attentively

The LLM that genuinely needs more data per page can override the default with a larger `limit`. The default protects the common case; the parameter enables the exception.

## Data hash: how snapshots are identified

The data hash field (`dh`) in the cursor identifies the snapshot of the graph data when the cursor was issued. This is what makes cursors stateless across server restarts.

The data hash should be derived from a stable identifier of the underlying data. Preferred sources, in order:

1. **jQAssistant scan ID.** If jQAssistant exposes a scan or build identifier, use it directly. Each rescan produces a new ID; the cursor's data hash matches the current ID iff the data hasn't changed.

2. **jQAssistant scan timestamp.** If jQAssistant doesn't expose a scan ID, the timestamp of the most recent scan works. The server captures this at load time and compares against cursor data hashes.

3. **Computed fingerprint.** If neither is available, compute a fingerprint at load time from the in-memory model's structural properties (total node count, hash of root node IDs, total edge count). Less authoritative — two different scans could theoretically produce the same fingerprint — but practically reliable.

The data hash is computed once at server startup (or after reload) and used for all cursors issued during that period. When the server restarts and reloads, if the underlying data is unchanged, the data hash is the same; cursors from before the restart still work.

## Error cases and recovery

When a cursor cannot be used, the response is a structured error indicating the problem and the recovery path:

### `INVALID_CURSOR_FORMAT`

The cursor string is not valid base64, or the decoded content is not valid JSON, or required fields are missing.

```json
{
  "error": {
    "code": "INVALID_CURSOR_FORMAT",
    "message": "The cursor is corrupted or malformed.",
    "recovery": "Restart pagination by calling the tool without a cursor parameter."
  }
}
```

### `STALE_CURSOR_VERSION`

The cursor's version field indicates a format the server no longer supports.

```json
{
  "error": {
    "code": "STALE_CURSOR_VERSION",
    "message": "The cursor was created with format version 1, but this server only supports versions 2 and above.",
    "cursor_version": 1,
    "supported_versions": [2],
    "recovery": "Restart pagination by calling the tool without a cursor parameter."
  }
}
```

This is rare in practice — versions only bump when the cursor format genuinely changes — but worth handling cleanly.

### `WRONG_TOOL_CURSOR`

The cursor was issued by a different tool than the one being called.

```json
{
  "error": {
    "code": "WRONG_TOOL_CURSOR",
    "message": "This cursor was issued by 'list_descendants' but you called 'outgoing_dependencies'.",
    "issued_by": "list_descendants",
    "called_on": "outgoing_dependencies",
    "recovery": "Use the cursor on the correct tool, or restart pagination on this tool by calling without a cursor."
  }
}
```

### `STALE_CURSOR_QUERY`

The cursor's query hash doesn't match the request's parameters — the LLM changed something between pages (a filter, a scope) that affects which results match.

```json
{
  "error": {
    "code": "STALE_CURSOR_QUERY",
    "message": "The query parameters differ from those used when this cursor was issued. Pagination cannot continue with different parameters.",
    "recovery": "To get more results for the new parameters, call without a cursor. To continue with the original parameters, restore them and retry."
  }
}
```

### `STALE_CURSOR_DATA`

The data hash in the cursor doesn't match the current data snapshot. The underlying graph data has changed since the cursor was issued (e.g., a jQAssistant rescan occurred).

```json
{
  "error": {
    "code": "STALE_CURSOR_DATA",
    "message": "The underlying graph data has changed since this cursor was issued. The cursor's position is no longer valid.",
    "recovery": "Reissue your original query (without the cursor) to get results from the current data."
  }
}
```

### `RESULT_TOO_LARGE`

Not a cursor fault. The resolved page's estimated wire size (`returned × bytes-per-item`) exceeds the server's response budget and would overflow the caller's context. Raised *before* the response is returned, so the harness never has to truncate it and offer generic "save-to-file and slice" advice. A single item (`limit=1`) is always allowed through, so the summary-only escape hatch can never itself be blocked.

```json
{
  "error": {
    "code": "RESULT_TOO_LARGE",
    "message": "This page (~60000 bytes for 6 items) exceeds the 50000-byte response budget and would overflow the caller's context.",
    "returned": 6,
    "estimated_bytes": 60000,
    "budget_bytes": 50000,
    "suggested_limit": 5,
    "recovery": "If you only need the ranking or breakdown, re-query with limit=1 and read the summary — these tools compute their summaries (e.g. by_target, by_source_type, by_kind) over the FULL result set, independent of page size, so a 1-item page still carries the complete answer. If you genuinely need the edges, lower the page size (e.g. limit=5) and walk next_cursor. Prefer either over dumping the result to a file and slicing it."
  }
}
```

For the cursor errors above, the recovery path is "restart pagination from a fresh call." The cursor mechanism doesn't try to recover from errors silently or partially — explicit failure with clear recovery is safer than guessing. `RESULT_TOO_LARGE` is the exception: its recovery is to shrink the page (or drop to the full-set summary with `limit=1`), not to restart.

## Server implementation notes

A few specifics worth flagging for the implementation:

**Cursor encoding is small and fast.** Encode using `Base64.getUrlEncoder().withoutPadding()`. Decode with the matching decoder. JSON serialization with Jackson or Gson is sub-millisecond for cursor-sized payloads.

**Query hashing should be deterministic.** When hashing the query parameters, serialize them in a stable order (sorted by key) and use a canonical representation (no trailing whitespace, consistent number formatting). Otherwise the same query at two different times could produce different hashes.

**The data hash is captured once.** At server startup or after reload, capture the current data hash and store it as a server-level constant. All cursors issued during that period use this hash. On reload, capture a new hash; cursors from the previous period are now stale.

**Iteration must be reproducible.** When implementing `list_descendants`, the traversal order needs to be deterministic across all factors that aren't part of the query. Same data, same query parameters → same result sequence, every time. This requires being explicit about ordering decisions (alphabetical fallback for ties, stable sort algorithms, etc.).

**Offset-based slicing is fine for now.** Each paginated tool computes the full result list and slices for the page. This is N×wasteful for large result sets with many pages, but for the sizes Hierograph deals with (tens of thousands at worst, typically hundreds), the slicing approach is acceptable and dramatically simpler than alternative cursor schemes (continuation tokens, key-based pagination, etc.). Revisit if profiling shows it matters.

**Concurrent cursor use is unrestricted.** Cursors are stateless, so multiple cursors can be in flight simultaneously without interaction. The server doesn't track which cursors have been issued or used. The LLM can paginate two different queries in parallel without coordination.

## How the LLM uses cursors

Two distinct workflows are worth distinguishing:

### Paginating through (exhaustive enumeration)

The LLM wants to process all results, regardless of size. Loop pattern:

1. Call the tool with the query parameters, no cursor.
2. Process the results in the response.
3. If `next_cursor` is present, call the tool again with the same parameters plus the cursor.
4. Repeat until `next_cursor` is absent.

This is appropriate when the LLM needs exhaustive coverage — e.g., "find every method in this module that throws IOException" where any of them might be the answer.

### Narrowing the query

The LLM realizes the result set is bigger than expected and reformulates the query with tighter filters. The cursor is ignored; a new query is issued without pagination.

This is usually the better workflow. If a query is truncated at 200 of 750 results, the LLM often doesn't want to process all 750 — it wants the most relevant ones, which are usually findable by narrowing the scope (smaller subtree input, more specific kind filter, etc.).

Both workflows are valid and should be supported. The tool description for each paginated tool should briefly mention both paths so the LLM understands the choice. Skills can give more detailed guidance about when each workflow applies.

## What's deliberately not in this design

A few choices made by exclusion, worth being explicit about:

**No expiration.** Cursors don't expire on a timer. They're invalidated by data changes (via the data hash) and by version bumps, but not by time. This avoids server-side TTL tracking and matches the stateless principle.

**No reverse pagination.** Cursors point forward only. There's no "previous page" cursor. The LLM that wants earlier results can re-issue the query from offset 0; given how fast Hierograph queries are, this is acceptable.

**No cursor inspection.** The server doesn't provide a tool to decode a cursor or report its state to the LLM. Cursors are opaque tokens; the LLM doesn't need to understand them.

**No total-pages metadata.** The response gives `total` (result count) and the page size is implicit from `returned`. Computing total pages is the LLM's job if needed, but typically the LLM follows cursors until `next_cursor` is absent rather than counting pages.

**No batch retrieval.** The LLM can't say "give me pages 5 through 10 in one call." Each page is a separate request. This keeps the API uniform and the cursor protocol simple.

## Summary

Pagination in Hierograph is opt-in per tool, applied only where response size can genuinely grow large. Cursors are self-contained tokens encoding version, tool, query hash, data hash, and offset. The design is stateless — cursors work across server restarts as long as the underlying data is unchanged. Stale cursors fail with clear errors that include recovery paths. The LLM uses cursors transparently, choosing between paginating through and narrowing the query based on what its task requires.

The paginated tools are `list_descendants`, `outgoing_dependencies` and `incoming_dependencies` at both detail levels, and `affected_by`. Page-size defaults are calibrated per tool against Claude Code's 10K-token warning threshold: 150 for `list_descendants`, 100 for type-level dependency tools and `affected_by`, 80 for detail-level dependency tools. Server-side caps prevent any `limit` value from producing pathologically large responses.
