# Hierograph Views — Specification

A **view** is an alternative hierarchy over the observed system: a JSON document
that groups nodes of the graph — modules, packages, types — into named groups,
possibly nested. This module defines the view document, its member selectors, and
how a document is resolved against a hierarchy, with the report of everything that
could not be applied.

It is a plain library, without Spring. It only reads hierarchies; creating the group
nodes in a fork of the hierarchy (materialization), the registry of view documents
and the tools' `view` parameter are built on top of it.

---

## Contents

1. [Where it sits](#1-where-it-sits)
2. [Views and groups](#2-views-and-groups)
3. [The document](#3-the-document)
4. [Member selectors](#4-member-selectors)
5. [Resolution](#5-resolution)
6. [API](#6-api)
7. [Boundaries](#7-boundaries)

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
 materialization: fork the hierarchy, create a local node per group, move the members
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
| restructuring field (`restructure`, `modules`, `moves`) | `moves: restructuring views are not supported yet` |
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
| `MEMBER_INSIDE_OWN_MEMBER` | warning | the inner member is dropped; its group already includes it |
| `MEMBERS_WITH_DIFFERENT_PARENTS` | error | the group is not applied; the finding names the parents; its applied subgroups are placed on their own |
| `EMPTY_GROUP` | warning | a group without members and without applied subgroups is not created |

Each finding carries the group's path, the selector where one is involved, a message,
and the nodes it is about.

---

## 6. API

| Type | Description |
|------|-------------|
| `ViewDocumentParser.parse(json)` | the `ViewDocument`, or `ViewDocumentException` with `problems` |
| `ViewDocument`, `GroupDefinition`, `MemberSelector` | the document as data |
| `NamePattern.compile(source)` | a name pattern; `matches(qualifiedName)`, `isExact` |
| `ViewResolver(hierarchy)` | indexes the hierarchy's qualified names on first use; one instance serves every view resolved against that hierarchy |
| `ViewResolver.resolve(document)` | the `ResolvedView` |
| `ViewResolver.select(selector)` | the nodes one selector selects, in pre-order |
| `ViewReport` | `findings`, `errors`, `warnings`, `hasErrors` |

`ViewResolver` is safe for concurrent use while the hierarchy does not change.

---

## 7. Boundaries

| Concern | Where |
|---------|-------|
| Group nodes: kind, source, handles | materialization (views design, part B) |
| Registry of project and state documents | graph service (views design, part B) |
| The `view` parameter and the view tools | MCP tools (views design, part C) |
| Restructuring: virtual modules and moves | rejected by the parser for now (views design, part D) |
