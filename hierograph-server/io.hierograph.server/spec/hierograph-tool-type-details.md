# Hierograph Tool `type_details` — Specification

`type_details` is Hierograph's **declaration view of a single type**. Given the id of
a class, interface, enum, record or annotation type, it returns what the type's
declaration says: its modifiers, its superclass, the interfaces it implements, the
annotations it carries, how many members of each sort it declares and the source file
it was compiled from. A language model calls it when it has found a type and needs to
know what that type *is* before it reasons about how the type is used.

This document explains why the tool exists, what it takes, where each fact comes
from, what it returns and how to use it. The concepts all tools share (node kinds,
NodeRefs, error shapes) are defined in the
[MCP tools specification](hierograph-mcp-tools.md).

---

## Contents

1. [Purpose](#1-purpose)
2. [Parameters](#2-parameters)
3. [Behaviour](#3-behaviour)
4. [Response](#4-response)
5. [Errors](#5-errors)
6. [Working with the tool](#6-working-with-the-tool)
7. [Guarantees](#7-guarantees)

---

## 1. Purpose

The browse tools (`find_node`, `list_children`, `list_descendants`) describe a type
with an [enriched NodeRef](hierograph-mcp-tools.md#62-enriched-noderef): its identity
and three member counts. That is enough to decide *where* to look, but not enough to
answer "is `Invoice` final?", "what does `Invoice` extend?" or "which interfaces does
it implement?". Those facts live in the type's declaration, and `type_details`
returns them in one call.

The tool has three design goals:

**Declaration facts, not usage facts.** The response describes the type as it is
declared: modifiers, supertypes, type-level annotations, member counts. How the type
*uses* other types (calls, field types, instantiations) is a dependency question and
belongs to `outgoing_dependencies`. Keeping the two apart keeps this response small
and its size independent of how busy the type is.

**Navigable references.** Superclass, interfaces and annotation types come back as
type references with ids, so a caller can pass them straight to the next
`type_details` (for a scanned type) or `incoming_dependencies` (for any node) call. A supertype that is not a node in the
graph still appears, by name, so the declaration is never silently incomplete (§3.2).

**Facts the browse tools cannot give.** The member summary splits constructors out of
the method count, which the enriched NodeRef does not, and the location names the
source file so the caller can open it.

### `type_details` and its neighbours

| Question | Tool |
|----------|------|
| How big is this type, and what is its parent? | the enriched NodeRef from `find_node` / `list_children` |
| What does this type extend, implement and carry as annotations? | `type_details` |
| Which methods and fields does it declare? | `list_children(nodeId = <type id>)` |
| What is the signature of one method, or the use of one field? | `method_details`, `field_details` |
| Which types does it depend on, and how strongly? | `outgoing_dependencies` |

The browse tools and `type_details` both describe a type, and both are needed:

1. Browse tools return hundreds of types per call; adding supertypes and annotations
   to each entry would multiply their size for facts the caller rarely needs.
2. `type_details` reads the type's rows from the Parquet snapshot on demand. The
   browse tools answer from the in-memory model and never touch the snapshot.
3. A caller needs the member list and the declaration for different reasons, so each
   has its own tool and neither pays for the other.

---

## 2. Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `typeId` | integer | yes | Id of a node of kind `java.class`, `java.interface`, `java.enum`, `java.record` or `java.annotation`. |

**`typeId`** is the only input because a type is fully identified by its id
([shared spec §5](hierograph-mcp-tools.md#5-parameters)). Every tool that returns a
type returns its id, so the caller never has to look a type up by name first. The id
must name a type defined in the scanned code. External types (kind `external.type`)
are rejected (§5): they have no declaration in the snapshot to describe.

---

## 3. Behaviour

### 3.1 Where each fact comes from

The tool combines the in-memory model with the type's rows in the Parquet snapshot,
described in the
[snapshot format specification](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md).

| Response field | Source | Why there |
|----------------|--------|-----------|
| `type`, `parent_container` | the model | identity and tree position are the model's job |
| `modifiers`, `is_abstract` | `types.parquet`, column `modifiers` | the model keeps no declaration flags |
| `location.source_file` | `types.parquet`, column `source_file` | same |
| `superclass` | the type's `EXTENDS` row in `refs.parquet` | keeps targets the model leaves out (§3.2) |
| `interfaces` | the type's `IMPLEMENTS` rows in `refs.parquet` | same |
| `annotations` | the type's `ANNOTATED_BY` rows in `refs.parquet` | same |
| `member_summary` | the type's rows in `members.parquet` | only the snapshot tells constructors from methods |
| `inner_types` | the type's children in the model | see below |
| `is_generic` | none | the snapshot does not record type parameters |

The `types.parquet` row is the one filed under the artifact of the type's own module,
so two artifacts that define the same FQN do not mix modifiers or source files. See
[`types.parquet`](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#41-typesparquet),
[`members.parquet`](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#42-membersparquet)
and
[`refs.parquet`](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#44-refsparquet)
for the meaning of each column.

**Superclass.** The `EXTENDS` row is the superclass named in the class file. Every
class file names one except `java.lang.Object`'s own, so the superclass of an
interface or an annotation type is `java.lang.Object`, of an enum `java.lang.Enum` and
of a record `java.lang.Record`. `superclass` is `null` only for a type whose class file
names no superclass.

**Interfaces.** For an interface, the interfaces it extends are listed here, because
the class file records them the same way as implemented interfaces. An annotation type
lists `java.lang.annotation.Annotation`. Each interface appears once.

**Annotations.** Only annotations on the type itself are listed, each once. Annotations
on members or parameters belong to `method_details` and `field_details`. Annotations with
`SOURCE` retention are not in the class file and therefore never appear. Every Kotlin
class carries `kotlin.Metadata`, which therefore appears here for Kotlin types.

**Nested types.** A nested type is a sibling of its enclosing type in the package, with
its binary name (`Invoice$Line`;
[shared spec §3.1](hierograph-mcp-tools.md#31-nodes-and-the-containment-tree)). A
type's children in the model are its members only, so `inner_types` is always an empty
list and `parent_container` is always the type's package. To find the nested types of
`Invoice`, list the package and look for names starting with `Invoice$`.

**Modifiers.** Modifiers are decoded from the
[modifier bits](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#51-modifier-bits)
in a fixed order: visibility first, then `abstract`, `final`, `static`.

| Modifier | When present |
|----------|--------------|
| `public`, `protected`, `private` | the declared visibility |
| `package-private` | no visibility bit set |
| `abstract` | abstract classes; every interface and annotation type |
| `final` | final classes; enums and records that the compiler marks final |
| `static` | static nested types |

Exactly one visibility value is always first. For a nested type the modifiers are its
*declared* ones (`private`, `static`, …), taken from the class's own `InnerClasses`
entry. `sealed`, `strictfp` and Kotlin-only modifiers such as `open` or `data` are
not reported, because the snapshot does not record them.

**Member counts.** `member_summary` counts every row in `members.parquet` for the type,
including compiler-generated members: bridge methods, synthetic accessors, lambda
bodies and the static initializer `<clinit>`. Constructors (`<init>`) are counted
separately in `constructor_count` and are *not* part of `method_count`. The enriched
NodeRef counts them as methods, so for the same type `list_children` reports
`method_count` = `type_details`' `method_count` + `constructor_count`.

### 3.2 Referenced types outside the graph

A superclass, interface or annotation type is reported as a **type reference**: a
NodeRef with `id`, `name`, `qualified_name` and `kind`, without the parent fields. How
it is filled depends on whether the referenced type is a node in the graph:

| Referenced type | `id` | `kind` | Example |
|-----------------|------|--------|---------|
| a type in the scanned code | its node id | its kind (`java.class`, …) | `com.example.billing.Ledger` |
| an external type with a placeholder node | the placeholder's id | `external.type` | `java.io.Serializable` |
| an external type without a node | `null` | `external.type` | `java.lang.Object`, `java.lang.Comparable`, `kotlin.Metadata` |

The graph does not create placeholder nodes for every external type. The
[placeholder policy](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore.md#6-external-types-and-placeholders)
leaves out ubiquitous names such as `java.lang.*`, `kotlin.*` and the JetBrains
`@NotNull` / `@Nullable` annotations, because a dependency on them carries no
architectural information. The type's `refs.parquet` rows still name them, so
`type_details` reports them with `id: null`. Without this fallback, every interface's
superclass and every Kotlin type's annotations would be missing from the response. The
`name` of such a reference is the part of the FQN after the last dot.

### 3.3 Worked example

The class

```java
package com.example.billing;

public final class Invoice implements Comparable<Invoice>, java.io.Serializable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Invoice.class);
    private final Ledger ledger;
    private final java.util.List<Line> lines;

    public Invoice(Ledger ledger) { this.ledger = ledger; this.lines = java.util.List.of(); }

    public com.example.money.Money total(String currency) { … }

    @Override
    public int compareTo(Invoice other) { … }

    public static final class Line { … }
}
```

compiles to two class files, `Invoice` and `Invoice$Line`. For `Invoice`,
`members.parquet` holds eight rows: three fields (`LOG`, `ledger`, `lines`), one
constructor, and four methods: `total`, `compareTo(Invoice)`, the bridge method
`compareTo(Object)` the compiler generates for the generic interface, and `<clinit>`,
which initialises `LOG`. `type_details` therefore reports `method_count` `4`,
`field_count` `3` and `constructor_count` `1`, while `list_children` reports
`method_count` `5` and `member_count` `8`.

`Comparable` is in `java.lang`, so it has no node and comes back with `id: null`.
`java.io.Serializable` has a placeholder node under `External Types` and comes back with
its id. `Invoice$Line` is not listed in `inner_types`; it is a sibling of `Invoice` in
package `com.example.billing`.

---

## 4. Response

### 4.1 Example

The response for `Invoice` (id `4711`) in package `com.example.billing` (id `4702`):

```json
{
  "type": {
    "id": 4711, "name": "Invoice", "qualified_name": "com.example.billing.Invoice",
    "kind": "java.class", "parent_id": 4702, "parent_kind": "java.package"
  },
  "parent_container": {
    "id": 4702, "name": "billing", "qualified_name": "com.example.billing",
    "kind": "java.package", "parent_id": 4701, "parent_kind": "java.package"
  },
  "modifiers": ["public", "final"],
  "is_abstract": false,
  "is_generic": null,
  "superclass": { "id": null, "name": "Object", "qualified_name": "java.lang.Object", "kind": "external.type" },
  "interfaces": [
    { "id": null, "name": "Comparable", "qualified_name": "java.lang.Comparable", "kind": "external.type" },
    { "id": 4807, "name": "Serializable", "qualified_name": "java.io.Serializable", "kind": "external.type" }
  ],
  "annotations": [],
  "member_summary": { "method_count": 4, "field_count": 3, "constructor_count": 1 },
  "inner_types": [],
  "location": { "source_file": "Invoice.java" }
}
```

### 4.2 Fields

| Field | Type | Null? | Description |
|-------|------|-------|-------------|
| `type` | minimal NodeRef | no | The type itself. |
| `parent_container` | minimal NodeRef | yes | The type's package. |
| `modifiers` | list of strings | yes | Declared modifiers, visibility first (§3.1). |
| `is_abstract` | boolean | yes | `true` when the `abstract` modifier is present. |
| `is_generic` | boolean | always `null` | Whether the type declares type parameters; not recorded. |
| `superclass` | type reference | yes | The superclass named in the class file. |
| `interfaces` | list of type references | no | Directly implemented (or, for an interface, extended) interfaces. |
| `annotations` | list of `{type}` | no | Annotations on the type itself; `type` is a type reference. |
| `member_summary` | object | no | `method_count`, `field_count`, `constructor_count`. |
| `inner_types` | list of minimal NodeRefs | no | Always empty (§3.1). |
| `location` | object | yes | `{source_file}`, the simple name of the source file. |

**`type`** is a [minimal NodeRef](hierograph-mcp-tools.md#61-minimal-noderef). It echoes
the input so a response can be read without the request, and its `parent_id` places the
type in the tree.

**`parent_container`** is the type's package as a minimal NodeRef, so a caller can
browse the type's neighbours with `list_children` without another lookup. It is `null`
only for a type whose parent is the root, which does not occur for scanned code.

**`modifiers`** and **`is_abstract`** answer the most common declaration questions
("can I subclass it?", "can I instantiate it?") without parsing source. `is_abstract`
duplicates the presence of `abstract` so a caller can test a boolean. Both are
`null` when the snapshot has no `types.parquet` row for the type in its own artifact;
`null` means *unknown*, not "no modifiers".

**`is_generic`** is always `null`. The field is kept so the response shape names the
question; `null` says the answer is not available, which is different from `false`.

**`superclass`**, **`interfaces`** and **`annotations`** are the type's structural
dependencies, the three `refs.parquet` kinds that describe how a type is defined rather
than how it is used. They are type references (§3.2) so the caller can follow each one.
`annotations` wraps each reference in `{"type": …}` so the entry shape matches
`method_details`' annotations.

**`member_summary`** sizes the type by sort of member (§3.1). `constructor_count` is
separate because "how many ways can this be built" and "how much behaviour does it
have" are different questions.

**`inner_types`** is always `[]`, because nested types are not children of their
enclosing type in the tree (§3.1).

**`location`** names the source file so a caller with file access can open it. It holds
no line number, because the snapshot records lines for member code only. It is `null`
when the class file carries no `SourceFile` attribute. Several types can share one
source file (nested types, Kotlin file facades).

---

## 5. Errors

| Code | Situation | Shape | Extra fields |
|------|-----------|-------|--------------|
| `NODE_NOT_FOUND` | `typeId` names no node | nested | `recovery`: use `find_node` |
| `WRONG_NODE_KIND` | `typeId` names a node that is not one of the five type kinds | nested | `actual_kind`, `declaring_type`, `recovery` |
| `DETAIL_REBUILDING` | the detail snapshot for the live graph is still being written | flat | none |

Error shapes and the no-graph response are defined in the
[shared spec §8](hierograph-mcp-tools.md#8-errors). `NODE_NOT_FOUND` and
`WRONG_NODE_KIND` use the nested shape with `recovery`; `DETAIL_REBUILDING` uses the flat
shape.

**`WRONG_NODE_KIND`** tells the caller what it passed and where to go instead:

```json
{ "error": {
    "code": "WRONG_NODE_KIND",
    "message": "Node 4715 is a 'java.method', not a type. type_details requires a type-kind node (java.class, java.interface, java.enum, java.record, java.annotation).",
    "actual_kind": "java.method",
    "declaring_type": {
      "id": 4711, "name": "Invoice", "qualified_name": "com.example.billing.Invoice",
      "kind": "java.class", "parent_id": 4702, "parent_kind": "java.package"
    },
    "recovery": "To inspect the method itself, use method_details(methodId: 4715)." } }
```

`declaring_type` is the parent of the node that was passed, as a minimal NodeRef: the
declaring type for a method or field, the parent package for a package or an external
type (a package under `External Types` for the latter), and `null` for a module. The
`recovery` names `method_details` for a method (constructors included), `field_details`
for a field, and `list_children` / `list_descendants` for anything else.

**`DETAIL_REBUILDING`** is returned after a rescan, while the Parquet snapshot of the
live graph is still being written. The tool never reads the previous snapshot, so it
never mixes facts from two scans. Retrying shortly succeeds. Ids are checked first, so
an unknown or wrong-kind id is reported as such even while the snapshot is rebuilding.

---

## 6. Working with the tool

**From a name to a declaration.** Find the type, then ask for its declaration:

```json
{ "tool": "find_node",    "arguments": { "name": "Invoice", "kindFilter": ["types"] } }
{ "tool": "type_details", "arguments": { "typeId": 4711 } }
```

**Walk up the inheritance chain.** Pass `superclass.id` back to `type_details` while its
`kind` is one of the five Java type kinds. The chain leaves the scanned code at the
first `external.type`: a placeholder id is accepted by the dependency tools but rejected
by `type_details`, and a `null` id is accepted by no tool. The `qualified_name` names
the supertype in both cases.

**Find implementations.** `type_details` lists what a type implements, not who
implements it. For the reverse question, use
`incoming_dependencies(fromId = <interface id>)` and read the edges flagged
`is_implements`.

**Open the source.** `location.source_file` is a simple file name. Combine it with
`parent_container.qualified_name` (`com/example/billing/Invoice.java`) to locate the file
under a source root.

**List the members.** `member_summary` only counts. Use
`list_children(nodeId = 4711)` to get the members with ids, then `method_details` or
`field_details` on the ones of interest.

**Find nested types.** `inner_types` is empty by construction. Use
`list_children(nodeId = <package id>, namePattern = "Invoice$")`.

---

## 7. Guarantees

1. **Read-only.** The tool changes no state.
2. **One snapshot.** All Parquet facts in a response come from the snapshot of the live
   graph; when that snapshot is not ready the tool returns `DETAIL_REBUILDING` instead of
   reading another.
3. **No duplicates.** Each interface and each annotation type appears at most once.
4. **Complete supertypes.** Every superclass and interface named in the class file is
   reported, whether or not it is a node in the graph.
5. **Ids are inputs.** Every non-null `id` in the response is a node id that other tools
   accept. A `null` id is never a valid input.
6. **Deterministic.** For the same graph and `typeId`, the response is the same.
