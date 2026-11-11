# Views — analysing in a view, and proposing one

A **view** is a named regrouping of the hierarchy that the server keeps: groups of
modules (or packages, or types) that sit above their members as nodes of kind
`view.group`. Multi-modules, layers, bounded contexts, team ownership and target
architectures are all views. Every structural tool takes an optional `view` and then
answers in that view; dependencies are the same in every view, only where nodes sit
changes. So a group-level DSM is one `pairwise_dependencies` call over group ids, and
the boxes of a layered graph are a view's groups instead of hand-collected lists.

Contents: [Use an existing view](#use-an-existing-view) · [Propose a
view](#propose-a-view) · [The document](#the-document) · [Reading the
report](#reading-the-report) · [Things that bite](#things-that-bite)

## Use an existing view

1. `list_views()` — the views there are: `source` `project` (versioned with the code,
   in `.hierograph-views/`) or `state` (defined at runtime), `valid`, and counts of
   groups, errors and warnings. An invalid view lists its `problems`; fix it before
   using it. `default` beside them is the hierarchy as scanned — what a call without
   `view` answers in; it is not a name to pass.
2. `graph_overview(view = "<name>")` — the view's top level (`hierarchy`: its groups
   and the modules no group takes) and a `view` section with the report. A report
   with errors means part of the document was not applied; say so before
   interpreting anything.
3. Pass the **same** `view` to **every** call of the analysis. A group id is unknown
   outside its view (`NODE_NOT_FOUND`), and a cursor issued in a view is rejected
   anywhere else (`STALE_CURSOR_DATA`).

**Node sets in a view** (SKILL.md step 1):

| Node set | How |
|----------|-----|
| the view's top level | the ids in `graph_overview(view).hierarchy` — groups plus ungrouped modules; drop "External Types" as usual |
| the members of one group | `list_children(nodeId = <group id>, view = "<name>")` |
| all groups with their ids and paths | `get_view(name).groups` (`path`, `id`, `parent_id`, `child_count`) |

A **group-level DSM** is then the ordinary step 2:
`pairwise_dependencies(nodeIds = [<group ids>], view = "<name>")`. Its cells are the
sums over the members, so `has_cycles` answers "are the groups layered?". That is
its own question: grouping can create a cycle between groups whose members are
acyclic, and can hide a member cycle inside one group. When the group level and the
module level disagree, report both.

**Layered graph with the view's groups as boxes** (`references/layered.md`): take
the members as the node set and the groups as the boxes.

1. From `get_view(name).groups`, pick the groups to draw — usually the innermost
   ones, whose children are modules; one box per node.
2. For each, `list_children(nodeId = <group id>, view = "<name>")` gives the member
   ids.
3. `pairwise_dependencies(nodeIds = [<all member ids>])` for the edges. Real nodes
   have the same ids and dependencies in every view, so the call needs no `view`.
4. In the input file, map each group's path to its member ids under `view_groups`
   (`{"web": [<id>, <id>]}`). The script draws one spanning box per group.

## Propose a view

When the user wants to see the system "by layer", "by multi-module", "by context",
or to check a target architecture, draft a view, define it, and iterate on the
report. Sources for a first draft:

| Grouping | Where the membership comes from |
|----------|---------------------------------|
| multi-modules | the build: each Maven aggregator's `<modules>`, a Gradle parent's subprojects. Not name similarity |
| layers | the dependency order: `pairwise_dependencies` over the modules, `topological_order` reversed; propose bands, let the user name and adjust them |
| bounded contexts | package or module name segments (`*.billing.*`, `*.shipping.*`), confirmed with the user |
| ownership, target architecture | the user; ask |

Then:

1. **Write the document** (below) and read the groups back to the user — like the
   node set, the grouping silently changes every later number.
2. **`define_view(document = {…})`.** It stores the view on the server (replacing a
   runtime view of the same name) and returns the applied groups with their ids and
   the report.
3. **Read the report** and fix what it names; define again. Repeat until it has no
   errors and the warnings are understood.
4. **Analyse** with the group ids: group-level DSM, layered graph.
5. **Keeping it.** A defined view lives in the server's state directory: the state
   volume when the server runs in Docker, `<build root>/.hierograph/runtime-views` when
   it runs as a local process. Neither is versioned (`.hierograph/` is git-ignored). To
   version it, the user saves the document as `.hierograph-views/<name>.json` in the
   build root and commits it; offer the content, and let them decide. Clean up experiments with `delete_view(name)`.

## The document

```json
{
  "name": "layers",
  "description": "Target layering",
  "groups": [
    { "name": "api",    "members": ["com.acme:acme-*-api:*"] },
    { "name": "domain", "members": ["com.acme:acme-{billing,shipping}:*"] },
    { "name": "infra",  "groups": [
        { "name": "persistence", "members": [{ "match": "com.acme:acme-*-jpa:*", "kind": "java.module" }] },
        { "name": "messaging",   "members": ["com.acme:acme-kafka:*"] } ] }
  ]
}
```

- `name`: lower-case letters, digits, `.`, `_`, `-`.
- A **selector** is a pattern over qualified names: a module's is its GAV
  (`group:artifact:version`, a test artifact with a trailing `:test`), a package's or
  type's its fully qualified name. `*` matches anything except `:` — so
  `com.acme:acme-core:*` matches the main artifact but **not** the test artifact —
  `**` matches anything, `{a,b}` alternatives. `*` does cross `.`: `com.acme.*`
  matches sub-packages too.
- An object selector narrows with `kind` (`java.module`, `java.package`, `types`, …)
  and `within` (a pattern of an enclosing node), for names that exist in a main and a
  test artifact alike.
- A node is in at most one group; the direct members of a group must share one
  parent (modules: the root). Overlapping groupings are separate views.
- Restructuring views (`"restructure": true`, `modules`, `moves`) move code; they
  belong to **hierograph-extract**.

## Reading the report

| Code | Meaning | Usual fix |
|------|---------|-----------|
| `SELECTOR_MATCHES_NOTHING` | a selector matches no node | check the GAV form and the version wildcard |
| `NODE_IN_TWO_GROUPS` | two groups select a node; the first keeps it | narrow one selector |
| `MEMBERS_WITH_DIFFERENT_PARENTS` | a group mixes levels (a module and a package) | split the group, or select at one level |
| `MEMBER_INSIDE_OTHER_GROUPS_MEMBER` | a group selects something inside another group's member | drop the inner selection |
| `EMPTY_GROUP` | nothing left to group | fix or remove the group |

Warnings leave the rest of the view applied; errors drop exactly what they name. A
report with zero errors is the precondition for claiming anything about the
grouping.

## Things that bite

- **Forgetting `view` on one call.** The answer silently comes from the hierarchy as
  scanned. Pass it on every call, and name the view in the writeup.
- **A GAV pattern needs its version part.** `com.acme:acme-core` matches nothing;
  write `com.acme:acme-core:*` (and `com.acme:acme-core:**` to take the test
  artifact as well).
- **Stacking order between groups without edges is an artifact**, in the DSM and the
  layered graph alike; say so rather than reading a dependency into it.
