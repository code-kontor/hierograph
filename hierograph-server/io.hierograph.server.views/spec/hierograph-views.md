# Hierograph Views — Specification

A **view** is an alternative hierarchy over the observed system: a JSON document
that groups nodes of the graph — modules, packages, types — into named groups,
possibly nested. A **restructuring view** also moves packages and types into other or
new modules and packages first: a virtual refactoring whose module dependencies and
cycles can be read before any code is touched (§6.4). This module defines the view document, its member selectors, and
how a document is resolved against a hierarchy, with the report of everything that
could not be applied.

The format, the selectors, resolution and materialization (§2–§6) are a plain
library in this module, without Spring; materialization only ever changes a fork.
The registry of view documents and the cache of materialized views (§7) live in the
graph service (`io.hierograph.server.graphservice.views`). The MCP tools' `view`
parameter, the view tools and the GraphQL `view` argument (§8) are built on top of
them.

---

## Contents

1. [Where it sits](#1-where-it-sits)
2. [Views and groups](#2-views-and-groups)
3. [The document](#3-the-document)
4. [Member selectors](#4-member-selectors)
5. [Resolution](#5-resolution)
6. [Materialization](#6-materialization)
7. [Registry and service](#7-registry-and-service)
8. [Tools and GraphQL](#8-tools-and-graphql)
9. [API](#9-api)
10. [Decisions](#10-decisions)
11. [Boundaries](#11-boundaries)

---

## 1. Where it sits

```
 view document (JSON)
        │  ViewDocumentParser.parse          → ViewDocument, or ViewDocumentException (§3)
        ▼
 ViewDocument ──┐
                │  ViewResolver(hierarchy).resolve   → ResolvedView = groups + ViewReport (§5)
 Hierarchy ─────┘
        │
        ▼
 ViewMaterializer.materialize            → MaterializedView: forked model + group nodes + report (§6)
        │
        ▼
 ViewService.view(name)                  → ViewSnapshot: a GraphSnapshot of the view, cached per graph snapshot (§7)
        │
        ▼
 MCP tools (view = name) · GraphQL hierarchicalGraph(view: name)   → answers in the view (§8)
```

| Depends on | For |
|------------|-----|
| `io.hierograph.hierarchicalgraph.model` | `Hierarchy`, `HGNode`, `NodeMetadata` (qualified names) |
| `io.hierograph.graphstore.jvm` | the Java node kinds and the kind aliases (`JavaKinds`) |
| Jackson (`jackson-databind`) | reading the JSON tree |

---

## 2. Views and groups

- A **group** has a name and **members**. Members are selected by qualified name
  (§4). A group may contain further groups.
- A node is in **at most one group** of a view. Overlapping groupings — layers and
  teams over the same modules — are separate views.
- All members of a group, and all the groups nested in it, share **one parent** in
  the base hierarchy. The group takes their place below that parent: grouping
  inserts levels, it never takes a node away from its parent.
- Nodes no group selects stay where they are.
- Sibling order carries no meaning (no tool depends on it), but it is
  deterministic: groups in document order, members in base-hierarchy order.

Grouping never changes a dependency, but it changes which cycles exist between the
nodes of one level: two acyclic sets of packages can form a cycle as groups, and a
cycle between packages disappears from the group level when both are in one group.

---

## 3. The document

```json
{
  "name": "multi-modules",
  "description": "The Maven aggregators of the reactor",
  "groups": [
    {
      "name": "hierograph-server",
      "members": [
        "io.hierograph:io.hierograph.server:*",
        "io.hierograph:io.hierograph.server.*:*"
      ]
    },
    {
      "name": "core",
      "description": "Everything the server builds on",
      "groups": [
        { "name": "hierograph-graphstore",
          "members": ["io.hierograph:io.hierograph.graphstore.*:*"] },
        { "name": "hierograph-hierarchicalgraph",
          "members": [{ "match": "io.hierograph:io.hierograph.hierarchicalgraph.*:*", "kind": "java.module" }] }
      ]
    }
  ]
}
```

| Field | Type | Rule |
|-------|------|------|
| `name` | string, required | the view's name; `[a-z0-9][a-z0-9._-]*` |
| `description` | string, optional | free text |
| `groups` | array, optional | the top-level groups; default empty |
| `groups[].name` | string, required | not blank, no `/`, unique among its siblings |
| `groups[].description` | string, optional | free text |
| `groups[].members` | array of selectors, optional | §4; default empty |
| `groups[].groups` | array, optional | nested groups; default empty |
| `restructure` | boolean, optional | `true` for a restructuring view; required for `modules` and `moves` (§6.4) |
| `modules` | array, optional | virtual modules: `name` (letters, digits, `.`, `_`, `-`; no `:` or `/`; unique) and `description` |
| `moves` | array, optional | moves, applied in document order: `select` (a selector, §4) and `to` (a target, §6.4.1) |

A group's **path** is the names from its top-level group down, joined by `/`
(`core/hierograph-graphstore`). Sibling names are unique, so paths are unique within
a view; the same name may appear in different branches.

**Parse errors.** `ViewDocumentParser` rejects a document that breaks any rule above,
with every problem and its location (`groups[1].members[0].kind: unknown kind …`):

| Problem | Example message |
|---------|-----------------|
| invalid JSON | `Invalid JSON at line 3, column 7: …` |
| not an object, missing or mistyped field | `name: required`, `groups: must be an array` |
| invalid view or group name | `name: 'Multi' is not a valid view name; …` |
| duplicate sibling name | `groups: the group name 'core' is used more than once among sibling groups` |
| unknown field | `groups[0].member: unknown field; allowed are name, description, members, groups` |
| `modules` or `moves` without `"restructure": true` | `moves: only a restructuring view has moves; set "restructure": true` |
| invalid or duplicate module name | `modules[0].name: 'a:b' is not a valid module name; …` |
| move without `select`, or with a malformed target | `moves[2].to: 'm/' is not a target; use a module, a virtual module or <module>/<package>` |
| malformed pattern or unknown kind in a selector | §4 |

A document with a parse error is not applied at all.

---

## 4. Member selectors

A selector is a string — a name pattern — or an object:

```json
{ "match": "io.hierograph.graphstore.jvm.parquet", "kind": "java.package",
  "within": "io.hierograph:io.hierograph.graphstore.jvm:*" }
```

| Field | Meaning |
|-------|---------|
| `match` | required; a name pattern (§4.1) matched against node qualified names |
| `kind` | optional; restricts the match to one kind (§4.2) |
| `within` | optional; a name pattern; restricts the match to the strict descendants of the nodes it matches (§4.3) |

Selectors match the **qualified names** the tools show: a module's coordinates
(`io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT`, a test artifact with a
trailing `:test`; for a scanned jar, its file name `spring-core-7.0.8.jar`), a
package's or a type's fully qualified name. The root is never selected.

### 4.1 Name patterns

A pattern matches the **whole** qualified name.

| Form | Matches |
|------|---------|
| `*` | any run of characters except `:` |
| `**` | any run of characters, `:` included |
| `{a,b}` | one of the alternatives; an alternative may be empty and may contain `*` and `**`, but no braces |
| anything else | itself |

`*` stops at `:` so that one `*` covers one coordinate: `io.hierograph:io.hierograph.server:*`
matches the main artifact, not `…:0.2.0-SNAPSHOT:test`. It does **not** stop at `.`:

| Pattern | Also matches | Use instead |
|---------|--------------|-------------|
| `spring-context-*.jar` | `spring-context-support-7.0.8.jar`, `spring-context-indexer-7.0.8.jar` | `spring-context-7.0.8.jar` |
| `org.springframework.core.io.*Resource` | `org.springframework.core.io.support.EncodedResource` | `org.springframework.core.io.{,ClassPath,FileSystem,Url}Resource` |

A pattern without `*` and braces is **exact** and is looked up directly. A pattern
is malformed when it is empty, nests `{`, has a `}` without `{`, or leaves a `{`
open; the parse error names the position.

### 4.2 Kind

`kind` is a node kind value — `java.module`, `java.package`, `java.class`,
`java.interface`, `java.enum`, `java.record`, `java.annotation`, `java.method`,
`java.field`, `external.type`, … — or one of the group aliases the tools accept:

| Alias | Kinds |
|-------|-------|
| `types` | `java.class`, `java.interface`, `java.enum`, `java.record`, `java.annotation` |
| `packages` | `java.package` |
| `members` | `java.method`, `java.field` |

An unknown kind is a parse error.

### 4.3 Within

The same package name exists in every artifact that has classes in it — in a
module's main and test artifact, in every Spring jar for `org.springframework`.
`within` picks the subtree: a node matches only if one of its ancestors matches the
`within` pattern. `within` is strict; it never selects the `within` node itself.

---

## 5. Resolution

`ViewResolver(hierarchy).resolve(document)` reads the hierarchy and returns the
groups that can be applied and a report. Selectors are resolved anew on every call,
so a pattern picks up a module that appeared since the last resolution.

### 5.1 Steps

1. **Select.** Every selector of every group, in document order (a group before its
   subgroups), yields its matching nodes.
2. **Assign.** Each selected node goes to the first group, in that order, that selects
   it.
3. **Drop nested members.** A member that lies inside another member — of any group —
   is dropped.
4. **Place, bottom-up.** A group is applied when its remaining members and its applied
   subgroups share one parent; that parent is the group's `parent`. A group whose
   subgroups are applied but which is not applied itself keeps them: they are placed on their
   own, below their own parent.

### 5.2 Result

| Type | Content |
|------|---------|
| `ResolvedView` | the document, the groups to place below their parent, and the `ViewReport` |
| `ResolvedGroup` | the group's definition, path, `parent` (in the base hierarchy), `members` in base-hierarchy order, nested `groups` in document order |

`ResolvedView.groups` holds every applied top-level group, and every applied group
whose enclosing group was not applied, in document order.

### 5.3 The report

A view with errors is still applied as far as it can be, so a large, mostly correct
document stays usable while it is being fixed.

| Code | Severity | Effect |
|------|----------|--------|
| `SELECTOR_MATCHES_NOTHING` | warning | the selector is ignored |
| `WITHIN_MATCHES_NOTHING` | warning | the selector cannot match and is ignored |
| `NODE_IN_TWO_GROUPS` | error | the node stays in the first group in document order; the finding is on the later group and names both |
| `MEMBER_INSIDE_OTHER_GROUPS_MEMBER` | error | the inner member is dropped |
| `MEMBER_INSIDE_OWN_MEMBER` | warning | the inner members are dropped; their group already includes them. One finding per group, with their number and one example |
| `MEMBERS_WITH_DIFFERENT_PARENTS` | error | the group is not applied; the finding names the parents; its applied subgroups are placed on their own |
| `EMPTY_GROUP` | warning | a group without members and without applied subgroups is not created |

Each finding carries the group's path, the selector where one is involved, a message,
and the nodes it is about. A restructuring view adds the findings of §6.4.4, which
name the move (`moves[2]`) or module (`modules[0]`) instead of a group; their
severity may also be `info`.

---

## 6. Materialization

`ViewMaterializer.materialize(base, resolved)` applies a resolved view to a
**fork** of the base model; the base model is never changed.

1. For each group of `ResolvedView.groups`, in document order, create a local node
   and add it below the group's `parent`.
2. Move the group's members below it, in base-hierarchy order.
3. Do the same for the nested groups, below their enclosing group's node.

The result, `MaterializedView`, holds the model, the group nodes by path, and the
report. When no group can be applied, the model is the base model itself, not a
fork.

### 6.1 Order

A child's position among its siblings carries no meaning: the navigation tools sort
children by qualified name, the DSM computes its own order, and aggregation ignores
order. The model's `addChild` and `move` append, and materialization keeps it that
way; it only makes the order deterministic — groups in document order, members in
base-hierarchy order — so the same document over the same graph gives the same
tree.

### 6.2 Group nodes

| Property | Value |
|----------|-------|
| Kind | `view.group` (`ViewGroups.KIND`) |
| Source | `SyntheticNodeSource`: name = the group's name, qualified name = `<view>:<path>` (`multi-modules:core/hierograph-graphstore`) |
| Handle | `NodeHandle.of("view:<view>:<path>")` |

A group's handle depends only on the view's name and the group's path: it is stable
across rebuilds and re-materialization, and distinct per view. Real nodes keep their
handles in every view.

A group handle must not be the handle of a node of the base hierarchy:
`lookupNode` searches a hierarchy's local nodes first, so the group would hide that
node, and the model rejects such a local node. Materialization checks every group
handle first; on a collision it applies **no** group and adds a
`GROUP_HANDLE_COLLISION` error naming the group and the node.

| Code | Severity | Effect |
|------|----------|--------|
| `GROUP_HANDLE_COLLISION` | error | the view is not applied |

### 6.3 What a view changes

Grouping never changes a fact. A group has no dependencies of its own; its
accumulated and aggregated dependencies are those of its members, so a group is a
first-class node for every analysis: the aggregated dependency between two groups
is the sum over their members, and a group-level DSM is the member-level DSM summed
into blocks.

### 6.4 Restructuring

Grouping inserts levels and never takes a node away from its parent. A
**restructuring view** does: it moves packages and types into other or new modules
and packages, and shows what the system would look like afterwards — its module
dependencies, its cycles, its layering. `ViewRestructurer` applies the moves to a
fork; the view's groups are then resolved against, and placed in, the restructured
hierarchy, so a group can collect virtual modules as well as real ones.

```json
{ "name": "extract-parquet", "restructure": true,
  "modules": [ { "name": "io.hierograph.graphstore.parquet" } ],
  "moves": [
    { "select": { "match": "io.hierograph.graphstore.jvm.parquet", "kind": "java.package",
                  "within": "io.hierograph:io.hierograph.graphstore.jvm:*" },
      "to": "io.hierograph.graphstore.parquet" } ] }
```

#### 6.4.1 Selection and targets

A move's selector is resolved against the **base** hierarchy, like a group's. Of the
selected nodes, those inside another selected node of the same move go with it and
are not moved on their own. A node two moves select is moved by the first
(`NODE_MOVED_TWICE` on the second).

A merge or a rename empties a package and puts its content into another one (§6.4.2).
A later move that selects such a package, or a sub-package of it, follows the
content: when it went into a package the view created, that package moves. When it
went into a package of the graph, which has content of its own, the move is skipped
(`MOVE_OF_MERGED_PACKAGE`): moving it would take that content too. The order of moves
therefore matters only where a package is merged into an existing one.

A move's **target** is resolved against the restructured hierarchy when the move is
applied, so a move can target a package an earlier move created:

| `to` | Target |
|------|--------|
| a virtual module's name | that module |
| a module pattern (`io.hierograph:io.hierograph.server:*`) | the one real module it matches; none is `UNKNOWN_MOVE_TARGET`, more than one `AMBIGUOUS_MOVE_TARGET` |
| `<module>/<package fqn>` | that package in that module, created with its path when missing |

#### 6.4.2 What a move does

| Moved node | Target | Effect |
|------------|--------|--------|
| a package | a module | the package, with its subtree, goes into the module under its **own qualified name**: the package path above it is reused where the module has it and created as virtual packages where it does not. A package of the same name already there **absorbs** it: the moved package's children join it, same-named sub-packages merging in turn |
| a package | a package | the package becomes a sub-package of the target, its qualified name the target's plus its simple name (a virtual rename): virtual packages of the new names take its children, sub-packages renamed alike, and the emptied real packages leave the view |
| a type | a module | the type goes into the package of its own package name in that module, created when missing |
| a type | a package | the type goes into that package |
| a module | a module | **merge**: the module's packages and types move into the target as above, and the emptied module leaves the view |

Further rules:

- **Nested types follow their type.** `Reader$Page` moves with `Reader`, unless a
  move selects it on its own.
- **Empty packages leave the view.** A package the moves leave without children
  disappears, and so do the packages above it that become empty in turn
  (`Hierarchy.remove`). A real module left empty stays (`REAL_MODULE_EMPTY`).
- **Real nodes keep their handles.** A moved package or type has the same handle in
  the base hierarchy and in the view, so results compare node by node. A renamed
  package is a new, virtual package; the real one leaves the view.
- **Facts are untouched.** A move changes where a node sits, never what it depends
  on. A virtual module's dependencies are the aggregation of what was moved into it.
- **What cannot move:** members, external code (the `External Types` module, its
  packages and its types), groups and the root (`CANNOT_MOVE`); a module into a package
  (`CANNOT_MOVE`); a package into its own subtree (`MOVE_INTO_OWN_SUBTREE`). Each skips
  that node only. `External Types` is no move target either (`UNKNOWN_MOVE_TARGET`):
  code that was not scanned does not depend on the project's code, and a move into it
  would invent dependencies and cycles.

#### 6.4.3 Virtual modules and packages

| Property | Virtual module | Virtual package |
|----------|----------------|-----------------|
| Kind | `java.module` | `java.package` |
| Source | `VirtualNodeSource`: name and qualified name = its `name` | `VirtualNodeSource`: name = last segment, qualified name = the package's |
| Handle | `NodeHandle.of("view:<view>:module:<name>")` | `NodeHandle.of("view:<view>:package:<module>/<fqn>")`, the module by its qualified name |
| Place | below the root, in document order | below its parent package, or the module |

Virtual modules have the kind of a module, so module-level analyses and `kind`
filters treat them as what they stand for. `ViewNodes.isVirtual(node)` tells them
apart, and the tools mark them `virtual: true` (§8.1). A virtual node's handle must
not be taken by a node of the graph; on a collision the whole view is not applied
(`VIRTUAL_HANDLE_COLLISION`). A virtual module no move fills stays, empty
(`VIRTUAL_MODULE_EMPTY`).

Code that reads a type's facts from its artifact must not take the artifact from the
type's module in the view: a moved type may sit in a virtual module.
`type_details` therefore finds the owning artifact through the GraphStore's own
containment.

#### 6.4.4 The report and the effect

| Code | Severity | Effect |
|------|----------|--------|
| `MOVE_SELECTS_NOTHING` | warning | the move is ignored |
| `UNKNOWN_MOVE_TARGET` | error | the move is skipped |
| `AMBIGUOUS_MOVE_TARGET` | error | the move is skipped; the finding names the matching modules |
| `NODE_MOVED_TWICE` | error | the first move in document order moves the node |
| `MOVE_INTO_OWN_SUBTREE` | error | that node's move is skipped |
| `CANNOT_MOVE` | error | that node's move is skipped |
| `MOVE_OF_MERGED_PACKAGE` | error | that node's move is skipped: an earlier move merged it into an existing package of the graph |
| `VIRTUAL_HANDLE_COLLISION` | error | the view is not applied |
| `VIRTUAL_MODULE_EMPTY` | warning | the module stays, empty |
| `REAL_MODULE_EMPTY` | info | the module stays, empty: it may be meant to go, or a move is missing |
| `SPLIT_PACKAGE` | info | a package now has types in more modules than in the base hierarchy |

The **effect** (`RestructuringEffect`, in `MaterializedView.effect`) compares the
modules of the restructured hierarchy, before the groups, with the base hierarchy's:
the module dependencies added, removed and changed in weight, by qualified name; the
module cycles — strongly connected components of more than one module — after and
before; and `newCycles`, those the restructuring creates: the cycles whose modules
were not all in one base cycle. A base cycle that a move shrinks is therefore not new;
one that gains a module — the new virtual module, typically — or merges with another
is, because modules that could be built apart before now cannot. It answers "does this
extraction introduce a cycle?" without a second query. Materializing a restructuring
view of the Spring 7.0.8 fixture, effect included, takes some 100–150 ms.

#### 6.4.5 Examples

The examples restructure the `io.hierograph` reactor itself. The figures were
measured by applying each document with `ViewMaterializer` to the graph of commit
`84e7741c` (0.3.0-SNAPSHOT, test artifacts included); they are type-level weights.
Module names are shortened: `graphstore.jvm` stands for
`io.hierograph:io.hierograph.graphstore.jvm:0.3.0-SNAPSHOT`, and so on.

**Example 1 — extract a package into a new module, and find the cycle.** The
snapshot format, `io.hierograph.graphstore.jvm.parquet` (35 types), is a candidate
for a module of its own. The first draft is the document at the top of §6.4: one
virtual module, one move.

```
base                                         view "extract-parquet"
graphstore.jvm                               graphstore.jvm
└─ io ─ … ─ io.hierograph.graphstore.jvm     └─ io ─ … ─ io.hierograph.graphstore.jvm
   ├─ (11 types: GraphStore, …)                 ├─ (11 types: GraphStore, …)
   ├─ detail   (12 types)                       ├─ detail
   ├─ model    (18 types)                       └─ model
   └─ parquet  (35 types)                    io.hierograph.graphstore.parquet        [virtual]
                                             └─ io ─ … ─ io.hierograph.graphstore.jvm [virtual packages]
                                                └─ parquet  (35 types, same handles)
```

| Effect | Weight |
|--------|--------|
| added `graphstore.parquet → graphstore.jvm` | 146 |
| added `graphstore.jvm → graphstore.parquet` | 9 — `detail` reads the snapshot |
| `new_cycles` | {`graphstore.parquet`, `graphstore.jvm`} |

The extraction as drafted creates a module cycle, and `outgoing_dependencies` from
`graphstore.jvm` to the new module, in the view, names the edges that cause it. The
report also has a `SPLIT_PACKAGE` info: the tests of `parquet` stay in
`graphstore.jvm`'s test artifact.

**Example 2 — fix the draft: move the detail queries along.** Nothing else in
`graphstore.jvm` depends on `detail`, so the second draft moves both packages:

```json
{ "name": "extract-parquet", "restructure": true,
  "modules": [ { "name": "io.hierograph.graphstore.parquet" } ],
  "moves": [
    { "select": { "match": "io.hierograph.graphstore.jvm.parquet", "kind": "java.package",
                  "within": "io.hierograph:io.hierograph.graphstore.jvm:*" },
      "to": "io.hierograph.graphstore.parquet" },
    { "select": { "match": "io.hierograph.graphstore.jvm.detail", "kind": "java.package",
                  "within": "io.hierograph:io.hierograph.graphstore.jvm:*" },
      "to": "io.hierograph.graphstore.parquet" } ] }
```

| Effect | Weight |
|--------|--------|
| added `graphstore.parquet → graphstore.jvm` | 217 |
| `graphstore.jvm → graphstore.parquet` | none |
| added consumers of the new module | `server` 32, `server.graphservice` 24, `graphstore.jvm.scanner` 14, `hierograph-maven-plugin` 6, and the test artifacts |
| `new_cycles` | none |

The added consumers are the list of builds that need a dependency on the new module
— the shopping list for the real refactoring. Defining the document again replaces
the first draft: draft, read the effect, adjust.

**Example 3 — split a module by types.** `server.graphservice` holds the graph
service and the file watching in one package. The watcher types depend only on each
other and on a few helpers:

```json
{ "name": "split-watching", "restructure": true,
  "modules": [ { "name": "io.hierograph.server.watch" } ],
  "moves": [
    { "select": { "match": "io.hierograph.server.graphservice.*Watcher", "kind": "types",
                  "within": "io.hierograph:io.hierograph.server.graphservice:*" },
      "to": "io.hierograph.server.watch" },
    { "select": { "match": "io.hierograph.server.graphservice.{WatchTargets,BuildFileTracker,OutputSignatureKt}",
                  "kind": "types", "within": "io.hierograph:io.hierograph.server.graphservice:*" },
      "to": "io.hierograph.server.watch" } ] }
```

Ten types move, nested and synthetic classes included. They keep their package: the
new module gets a virtual `io.hierograph.server.graphservice` package, and the report
notes the split package. The effect: `server.graphservice → server.watch` is added
with weight 13, nothing points back, no cycle. Targeting the package
`io.hierograph.server.watch/io.hierograph.server.watch` in both moves instead gives the
types a package of their own and removes the split, with the same effect. A further
move cannot do it: moves select in the base hierarchy, where that package does not
exist.

**Example 4 — move one type to where its knowledge lives.** `HierarchyResolution`
resolves overrides and virtual calls over JVM types; apart from its nested types it
depends only on `graphstore.jvm`:

```json
{ "name": "jvm-resolution", "restructure": true,
  "moves": [ { "select": { "match": "io.hierograph.server.graphservice.HierarchyResolution", "kind": "types" },
               "to": "io.hierograph:io.hierograph.graphstore.jvm:*/io.hierograph.graphstore.jvm.detail" } ] }
```

No module dependency is added or removed; only weights change, which is why the
effect lists `changed`. `server.graphservice → graphstore.jvm` falls from 222 to 203:
the type's own dependencies on `graphstore.jvm` leave the module and outweigh the new
use of the moved type. The test artifact that exercises the type
(`server.graphservice:test → graphstore.jvm`, 78 → 108) shows where its tests would
have to go.

**Example 5 — merge two modules.** `server.mapping` connects the GraphStore to the
hierarchical graph. Merging it into the graph service:

```json
{ "name": "merge-mapping", "restructure": true,
  "moves": [ { "select": "io.hierograph:io.hierograph.server.mapping:*",
               "to": "io.hierograph:io.hierograph.server.graphservice:*" } ] }
```

`*` does not cross `:`, so the selector takes the main artifact only; the test
artifact stays, and the report notes the split package. `:**` would take the test
artifact too, but merge it into the graph service's *main* artifact. The precise
view adds a second move, from `…mapping:*:test` to `…graphservice:*:test`.

| Effect | Weight |
|--------|--------|
| removed `server.graphservice → server.mapping` | 15, now internal |
| removed `server → server.mapping`; changed `server → server.graphservice` | 15; 241 → 256 |
| changed `server.graphservice → hierarchicalgraph.model` | 142 → 187, the mapping's 45 |
| changed `server.graphservice → graphstore.jvm` | 222 → 253, the mapping's 31 |

One module fewer, no new edge between the remaining main artifacts, no cycle.

**Example 6 — a target architecture: restructuring and grouping together.** A view
can describe where the system should go as a whole: the moves of examples 2 and 3,
and groups over real and virtual modules.

```json
{ "name": "target-2027", "restructure": true,
  "modules": [ { "name": "io.hierograph.graphstore.parquet" }, { "name": "io.hierograph.server.watch" } ],
  "moves": [ "the four moves of examples 2 and 3" ],
  "groups": [
    { "name": "core",    "members": ["io.hierograph:io.hierograph.hierarchicalgraph.*:*",
                                     "io.hierograph:io.hierograph.graphstore.jvm:*"] },
    { "name": "storage", "members": ["io.hierograph.graphstore.parquet",
                                     "io.hierograph:io.hierograph.graphstore.jvm.scanner:*"] },
    { "name": "server",  "members": ["io.hierograph:io.hierograph.server*:*",
                                     "io.hierograph.server.watch"] } ] }
```

(`moves` stands for the four move objects; the parser takes objects only.) The
group-level DSM is the target's layering: `storage → core` 294, `server → core`
1892, `server → storage` 87, and nothing pointing up. Kept in `.hierograph-views/`,
the document is the architecture decision in reviewable form, and every rebuild
re-checks the code against it.

**From an assistant.** The hierograph-extract skill turns "extract the snapshot
format into its own module" into the first draft, reads `new_cycles`, finds the edges
behind the cycle in the view, proposes the smallest additional moves that remove it
— moving `detail` along — and defines the next draft. The result is a view known to
be acyclic, and the list of build changes the real refactoring needs.

---

## 7. Registry and service

### 7.1 Where documents come from

| Source | Location (property) | Written by the server | For |
|--------|---------------------|-----------------------|-----|
| project | `hierograph.views.projectDir`, default `<build root>/.hierograph-views` | no | views kept with the code: versioned, reviewed, shared |
| state | `hierograph.views.stateDir`, default `<state dir>/runtime-views` | yes | views defined at runtime |

Every `*.json` file directly in one of the directories (not starting with `.`) is a
view document, named by its `name` field, not by its file name. The project default
is `.hierograph-views`, beside `.hierograph/` and not inside it: `.hierograph/` holds
the manifest and the server's state and is git-ignored, while project views are
versioned with the code, so a project ignores `.hierograph/` and commits
`.hierograph-views/`. Without a configured state directory the state directory is the
manifest's directory, `<build root>/.hierograph`, so runtime views default to
`<build root>/.hierograph/runtime-views`: on the build machine, but never versioned. When both properties name the same directory,
its documents are state views.

| Situation | Effect |
|-----------|--------|
| A name in both directories | the state document applies and is marked as shadowing the project one; deleting it restores the project view |
| A name twice in one directory | the first file in file-name order applies; the others are listed as invalid |
| A document that does not parse | listed under its file name without `.json`, with its problems; it is not applied |
| A file added, edited or removed | takes effect with the next request: every read compares the files' modification times and sizes with the last read |

`define(json)` parses the document and writes it as `<state dir>/<name>.json`
(atomically, replacing a state view of the same name, also one stored under another
file name); an invalid document is rejected and nothing is written. `delete(name)`
removes a state view; a project view is changed in the project, not through the
server.

### 7.2 Views as snapshots

`ViewService.view(name)` applies a view to the graph snapshot the calling thread
reads — the pinned one inside a tool call, else the live one — and returns a
`ViewSnapshot`: the registry entry, the materialized view, and a `GraphSnapshot` of
the view.

| Snapshot field | In the view's snapshot |
|----------------|------------------------|
| `model` | the materialized fork |
| `searchIndex` | the base index plus the group nodes; the entries of real nodes are shared |
| `dataHash` | the base data hash combined with the document's text: a cursor issued in a view is rejected when the graph or the document changes |
| `view` | the view's name |
| everything else | the base snapshot's: graph store, member handles, generation |

`ViewService.withView(view) { … }` runs a block in a view: it pins the view's
snapshot to the thread, so every read of the graph service inside sees the view's
hierarchy, and `currentView()` returns the view, report included. Asking for a view
inside `withView` materializes it from the base snapshot of the view in use, so views
never stack. A view snapshot pinned without `withView` cannot serve as a base
(`IllegalStateException`), and `withView` inside a call already pinned to another
snapshot fails rather than silently answering from the wrong graph.

### 7.3 Caching

A view is materialized lazily, on its first request against a snapshot, and cached
until the graph or the document changes:

1. The first request for a view and snapshot materializes it under a lock per view,
   so concurrent first requests build it once.
2. Only views of the **live** snapshot are cached. A call still pinned to a replaced
   snapshot gets a view built for it, without evicting the live one.
3. When a new snapshot is published, the views of the replaced one are dropped, so an
   idle view does not keep an old graph alive.
4. A changed document (another text) is materialized again on its next request.

A materialized view is never changed after it is returned: the model's reads are
thread-safe, its changes are not. Materializing a view of the Spring 7.0.8 fixture
(22 jars) takes some 40 ms.

| Failure | Exception |
|---------|-----------|
| unknown name | `UnknownViewException`, listing the known views |
| document does not parse | `InvalidViewException`, with the problems |
| invalid document passed to `define` | `ViewDocumentException`, with the problems |
| project view passed to `delete` | `IllegalArgumentException` |

---

## 8. Tools and GraphQL

### 8.1 The `view` parameter

Every MCP tool that reads the graph takes an optional `view` parameter: the name of a
view to answer in. The tools themselves do not declare it.
`GraphAvailabilityToolCallbackProvider` adds it to their input schema, takes it out
of the call's input, and runs the call inside `ViewService.withView`. A tool thus
answers in a view without knowing about views:

- navigation (`graph_overview`, `list_children`, `list_descendants`) walks the
  view's tree; the groups are nodes of kind `view.group`;
- `find_node` also finds the groups, by name or with the kind filter `view.group`; in
  a restructuring view it finds the virtual modules and packages, and no longer the
  real packages the moves removed, because the view's search index is built from its
  own hierarchy;
- every tool that takes node ids accepts a group's id: a group scope is the subtree
  below it in the view, so aggregated, pairwise, type-level and detail-level
  queries, `affected_by` and `find_dependency_path` work on groups unchanged;
- a cursor holds only in the view it was issued in, because the view's data hash
  covers the document (§7.2); used elsewhere it is rejected as `STALE_CURSOR_DATA`.

Without `view`, or with a blank one, a tool answers from the hierarchy as scanned,
exactly as before. The tools that manage views and `reload_graph` take no `view`.

| Situation | Answer, without running the tool |
|-----------|----------------------------------|
| `view` names no view | `VIEW_NOT_FOUND`, listing the known views |
| the view's document does not parse | `VIEW_INVALID`, with the problems |
| `view` is not a string (a number, an array, an object) | `INVALID_PARAMETER`; it is not taken as "no view" |

A group in a tool result is an ordinary node reference with `kind: "view.group"`,
its qualified name `<view>:<path>`, and `child_count`, `descendant_module_count` and
`descendant_type_count`. `graph_overview` in a view lists the view's top level —
the groups and the nodes no group takes — names `view.group` in its kind vocabulary,
and adds a `view` section: the view's entry, its group, error and warning counts,
and its report.

In a restructuring view, every node reference and slim node entry of a virtual
module or package carries `virtual: true`; real nodes, moved or not, carry no flag
and keep their ids. A moved type's details (`type_details`) are read from the
artifact that defines it, wherever the view places it.

### 8.2 View tools

Each view tool has its own specification in the server module:
[`define_view`](../../io.hierograph.server/spec/hierograph-tool-define-view.md),
[`list_views`](../../io.hierograph.server/spec/hierograph-tool-list-views.md),
[`get_view`](../../io.hierograph.server/spec/hierograph-tool-get-view.md) and
[`delete_view`](../../io.hierograph.server/spec/hierograph-tool-delete-view.md).

| Tool | Effect |
|------|--------|
| `define_view(document)` | validates the document (a JSON object), stores it as a state view replacing one of the same name, applies it; returns the entry, the applied groups (path, id, parent id, child count) and the report. An invalid document is an `INVALID_VIEW_DOCUMENT` error with the problems; nothing is stored |
| `list_views()` | every view: name, description, source (`project`/`state`), file, shadowing, validity; a valid view with its group, error and warning counts, an invalid one with its problems. Beside the views, `default` describes the hierarchy as scanned, which a call without `view` answers in: a description and its `top_level_count` |
| `get_view(name)` | the entry, the document, the applied groups and the full report; for an invalid document its problems |
| `delete_view(name)` | removes a state view; returns the project view that applies again, or `null`. A project view is a `PROJECT_VIEW` error |

For a restructuring view, `define_view`, `get_view` and the `view` section of
`graph_overview` add `virtual_modules` (name, id, child count) and `restructuring`,
the effect of §6.4.4: `added` and `removed` module dependencies (`from`, `to`,
`weight`), `changed` ones (`weight_before`, `weight_after`), `cycles`, `new_cycles`
and `introduces_cycles`. `list_views` and the summaries add `restructure`,
`virtual_module_count` and `introduces_cycles`. Each dependency list holds at most
100 entries (`<list>_count` then gives the total).

A report lists at most 50 findings (`finding_count` then gives the total), errors
first, then warnings, then infos, so the cap never hides an error; each lists at most
10 nodes (`node_count` then gives the total). It counts `errors`, `warnings`
and `infos`. `list_views`, `get_view` and
`delete_view` work without a loaded graph; without one, the listing and `get_view`
leave out what applying the view would report, and `default` its
`top_level_count`. `default` is not a view name: no view is reserved for the scanned
hierarchy, and `view = "default"` names a view like any other.

### 8.3 GraphQL

`hierarchicalGraph(view: String)` answers in the view: `QueryController` resolves
it and stores the view's snapshot as the request's view, and `GraphSnapshotPinning`
pins it around every data fetcher that follows and in the properties batch. Group
nodes have the type `view.group`, `findNodes` finds them, and their ids work in every
field that takes node ids.

A request answers in one view. A second `hierarchicalGraph` field that names another
view, or none, is a `BAD_REQUEST`. An unknown view is a `VIEW_NOT_FOUND` error, an
invalid one a `VIEW_INVALID` error (`ViewExceptionResolver`). Defining views stays
with the MCP tools.

---

## 9. API

| Type | Description |
|------|-------------|
| `ViewDocumentParser.parse(json)` | the `ViewDocument`, or `ViewDocumentException` with `problems` |
| `ViewDocument`, `GroupDefinition`, `MemberSelector` | the document as data |
| `NamePattern.compile(source)` | a name pattern; `matches(qualifiedName)`, `isExact` |
| `ViewResolver(hierarchy)` | indexes the hierarchy's qualified names on first use; one instance serves every view resolved against that hierarchy |
| `ViewResolver.resolve(document)` | the `ResolvedView` |
| `ViewResolver.select(selector)` | the nodes one selector selects, in pre-order |
| `ViewReport` | `findings`, `errors`, `warnings`, `hasErrors` |
| `ViewMaterializer.materialize(base, document \| resolved)` | the `MaterializedView`: `model`, `groupNodes` by path, `report`; for a restructuring view also `virtualModules` and `effect` |
| `ViewGroups` | `KIND`, `qualifiedName(view, path)`, `handle(view, path)`, `isGroup(node)` |
| `ViewRestructurer.restructure(base, document)` | the `Restructured` fork: `model`, `virtualModules`, `findings`, `effect`, `applied` |
| `ViewNodes` | `moduleHandle(view, name)`, `packageHandle(view, module, fqn)`, `isVirtual(node)` |
| `RestructuringEffect` | `added`, `removed`, `changed`, `cycles`, `baseCycles`, `newCycles` |
| `ViewRegistry(projectDir, stateDir)` | `entries()`, `find(name)`, `names()`, `define(json)`, `delete(name)` (graph service) |
| `ViewService` | `view(name)`, `entry(name)`, `entries()`, `define(json)`, `delete(name)`, `withView(view) { … }`, `currentView()` (graph service, Spring) |
| `ViewParameter` | `addTo(definition)`, `split(input)`: the tools' `view` parameter (MCP server) |
| `ViewTools` | `define_view`, `list_views`, `get_view`, `delete_view` (MCP server) |

`ViewResolver` is safe for concurrent use while the hierarchy does not change.

---

## 10. Decisions

The choices that shaped views, with the reason for each. They were taken in the
design of views (2026) and are recorded here because the code shows what was
decided, not why.

1. **"View" and `view.group`.** An alternative hierarchy defined by a document is a
   *view*; its group nodes have the kind `view.group`. The word says that nothing
   about the code changes, only how it is looked at.
2. **Views replace the *module groups* design.** That design carried one grouping,
   the build's multi-modules, through the manifest, the plugin, the GraphStore, the
   snapshot format and the tools. A view solves the general case in one place, with
   no change to any of them; multi-modules are one view document among many.
3. **Grouping first, restructuring behind a flag.** Pure grouping (members share a
   parent, levels are inserted) came first; moves followed, and need an explicit
   `"restructure": true`, so a grouping view can never move code by accident.
4. **Selectors on qualified names**, with `*`, `**`, `{a,b}` and optional `kind` and
   `within`, resolved anew for every graph generation. Qualified names are what the
   tools show and what a person or an assistant writes; resolving per generation lets
   a pattern pick up a module that appears later. `*` crosses `.` but not `:`, so a
   GAV pattern keeps main and test artifacts apart.
5. **A node is in at most one group per view.** Otherwise a node would sit in two
   places of one tree. Overlapping groupings, such as layers and teams over the same
   modules, are separate views.
6. **Two sources, the state wins.** Project views are versioned and reviewed with the
   code; state views are runtime experiments. A state view shadows a project view of
   the same name without touching the project. Project views default to
   `<build root>/.hierograph-views`, outside `.hierograph/`, which holds generated
   files and is git-ignored; state views default to `<state dir>/runtime-views`. Documents are re-read on
   access when their files change, not watched: views are only read on request.
7. **Lazy materialization per snapshot**, cached until the graph or the document
   changes, and only for the live snapshot. A view costs nothing until it is used,
   and an idle view does not keep a replaced graph in memory.
8. **A `view` parameter, not session state.** Each call names its view, so calls are
   independent, cursors stay verifiable, and two clients can use different views at
   once. The tool wrapper adds the parameter centrally, so every tool — present and
   future — supports it without code of its own. GraphQL answers one view per
   request, because the properties batch cannot tell fields apart.
9. **Virtual modules are `java.module`** (and virtual packages `java.package`),
   marked `virtual: true`, so module-level analyses, DSMs and kind filters treat them
   as what they stand for.
10. **Moves keep qualified names.** A package moved into a module keeps its name and
    gets its package path there; a type moved into a module lands in the package of
    its own package name. Renaming happens only when a move targets a package, and
    moving a module into a module merges them. This matches what the real refactoring
    would do in the common case.
11. **The report carries the module-level effect** — added, removed and changed
    module dependencies, and the module cycles before and after — so a draft is
    judged without further queries. Changed weights are part of it because a move
    between existing modules often changes no edge, only its weight (example 4).
    Nodes a restructuring empties leave the view through `Hierarchy.remove`.
12. **Sibling position is not modelled.** Groups are appended under their parent in
    document order, members in base-hierarchy order; the model has no positional
    insert, because no tool or analysis depends on sibling order (§6.1). Only
    determinism matters.
13. **The model guards the tree, materialization reports.** The model rejects a move
    into a node's own subtree, a second placement and a taken local identifier
    (hierarchical-graph model specification, §7.2, §7.3); materialization checks
    before it changes anything and turns each case into a report entry instead of
    failing the request.

---

## 11. Boundaries

| Concern | Where |
|---------|-------|
| Analyses and the layered graph in a view, proposing views | the hierograph skills (views design, part E) |
