# Hierograph Tool `field_details` — Specification

`field_details` is Hierograph's **declaration and usage view of a single field**. Given
a field id, it returns the field's modifiers, its type and annotations, and a digest of
the methods that read and write it: how many there are, a sample of them, and which
types they belong to. A language model calls it when it needs to know what a field
holds and how widely it is used, for example before changing its type or making it
private.

This document explains why the tool exists, what it takes, where each fact comes from,
what it returns and how to use it. The concepts all tools share (node kinds, NodeRefs,
the slim node map, error shapes) are defined in the
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

`list_children` on a type returns its fields named by signature:
`com.example.billing.Ledger ledger`. That gives the field's type, but not its
modifiers, its annotations, or who uses it. For a field, use is the interesting part:
a field read from forty methods in twelve types is a hidden coupling point that a
dependency matrix shows only as weight between types. `field_details` names those
methods and types directly.

The tool has three design goals:

**Usage as a digest, not a list.** A logger or a shared constant can be read by
hundreds of methods. The tool returns exact counts, a sample of at most ten methods and
a ranking of the declaring types, so the answer to "how widely is this used, and by
whom?" stays the same size however popular the field is.

**Counts are exact.** `method_count` and the per-type counts cover every accessing
method in the snapshot, not just the sample. A caller can rely on them for impact
decisions without paging.

**Declaration and use together.** Type, modifiers and annotations come in the same
response as the access digest, because the question "can I change this field?" needs
both: what it is, and who would notice.

### `field_details` and its neighbours

| Question | Tool |
|----------|------|
| Which fields does this type declare? | `list_children(nodeId = <type id>)` |
| What is this field, and how widely is it read and written? | `field_details` |
| List every reader or writer, with source lines | `incoming_dependencies` with `detailLevel` `"detail"` |
| What is the signature of a method that uses it? | `method_details` |

Unlike `method_details`, this tool uses the
[slim node map](hierograph-mcp-tools.md#63-slim-node-map). An access digest names up to
ten methods and their declaring types, and the same types recur across the read and
write digests, so referring to nodes by id keeps the response small.

---

## 2. Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `fieldId` | integer | yes | Id of a node of kind `java.field`, as returned by `list_children`, `find_node` or the detail-level dependency tools. |

**`fieldId`** is the field's node id. A field is fully identified by it
([shared spec §5](hierograph-mcp-tools.md#5-parameters)), so the tool needs no other
input. The digest has no size parameter because its caps (§3.3) already bound the
response.

---

## 3. Behaviour

### 3.1 Where each fact comes from

The tool resolves `fieldId` to the field's **member key** (`<owner FQN>#<type> <name>`,
see
[member signature and member key](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#33-member-signature-and-member-key))
and reads the snapshot described in the
[snapshot format specification](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md).

| Response field | Source |
|----------------|--------|
| `field`, `modifiers`, `is_constant`, `location` | the field's row in `members.parquet` |
| `type`, `type_name` | the field's `has_type` edge in `detail_refs.parquet` |
| `annotations` | the field's `annotated_by` edges in `detail_refs.parquet` |
| `read_access` | the `reads_field` edges in `detail_refs.parquet` that target the field |
| `write_access` | the `writes_field` edges in `detail_refs.parquet` that target the field |
| `declaring_type` | the owner FQN in the member key, resolved against the graph |

**Modifiers.** Modifiers are decoded from the
[modifier bits](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#51-modifier-bits)
in a fixed order: one visibility value (`public`, `protected`, `private` or
`package-private`), then any of `static`, `final`, `transient`, `volatile`. The
`synthetic` marker is not reported.

**Constant.** `is_constant` is `true` when the field is both `static` and `final`. It
does not mean the field is a compile-time constant: a `static final Money ZERO` is
reported as constant, too.

**Type.** The field's type is its erased type reduced to the element type: a
`List<Line>` field reports `java.util.List`, a `Money[]` field reports
`com.example.money.Money`. `type_name` always carries the name; `type` carries the id
when the type is a node:

| Field type | `type` | `type_name` |
|------------|--------|-------------|
| a type in the scanned code | its node id | its FQN |
| an external type with a placeholder node | the placeholder's id | its FQN |
| an external type without a node (`java.lang.*`, `kotlin.*`) | `null` | its FQN |
| a primitive | `null` | the keyword: `int`, `boolean`, … |
| no `has_type` edge in the snapshot | `null` | `unknown` |

The scanner records a `has_type` edge for every field, so `unknown` occurs only for a
snapshot that lacks it. Types without a node are explained by the
[placeholder policy](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore.md#6-external-types-and-placeholders).

**Annotations.** The annotations on the field, each once, as `{"type": <id>}`. Only
annotation types that are nodes in the graph are listed; an annotation whose type has
no node (`java.lang.Deprecated`, `org.jetbrains.annotations.NotNull`) is left out,
because the slim encoding has no way to name a node without an id. Annotations with
`SOURCE` retention are not in the class file and never appear.

**Location.** The snapshot records line numbers for code only, and a field has none
(see
[line numbers](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#34-line-numbers)).
`location` is therefore `null` for every field. To find the declaration, open the
declaring type's source file (`type_details`' `location.source_file`).

### 3.2 Who reads and writes a field

A `reads_field` or `writes_field` edge is recorded for each field instruction in a
method body. The access digests are built from the edges that target this field's
member key.

**Accessors are members.** Every member whose code touches the field counts: methods,
constructors (which write `final` fields), the static initializer `<clinit>` (which
writes `static` fields), lambda bodies and compiler-generated accessors. Code outside
a Kotlin class reaches a property through its getter, so for a Kotlin property the
getter is usually the only reader outside the declaring class.

**Each method counts once.** A method that reads the field five times counts as one
reader. `method_count` is the number of distinct accessing methods.

**Accesses are named by the instruction's owner.** The compiler names a field access
after the type it was written against. An access written against a subclass
(`SubInvoice.ledger` for a field inherited from `Invoice`) targets the subclass's name
and is not counted for `Invoice`'s field.

**Compile-time constants are inlined.** The Java compiler copies the value of a
`static final` field with a constant initializer (a primitive or a `String` literal)
into every reader, so no read instruction remains. Such a field reports
`read_access.method_count` `0` however often it is used in source.

### 3.3 The access digest

`read_access` and `write_access` have the same shape:

| Field | Type | Description |
|-------|------|-------------|
| `method_count` | integer | Number of distinct accessing methods. |
| `methods_sample` | list of ids | Up to 10 accessing methods. |
| `sample_truncated` | boolean | `true` when `method_count` exceeds the sample. |
| `by_declaring_type` | list of `{type, count}` | Accessing methods per declaring type, at most 10 entries. |
| `others_count` | integer | Number of declaring types beyond the 10 listed; present only when there are more. |

**`methods_sample`** lists the accessing methods ordered by their declaring type's FQN,
then by member key, and takes the first ten. The order is a stable choice, not a
ranking. Every sampled method and its declaring type have an entry in `nodes`.

**`by_declaring_type`** groups the accessing methods by declaring type and sorts the
groups by count, highest first; ties keep FQN order. The top ten are listed. This is the
answer to "which types depend on this field, and how much?", and its counts are exact.

**`others_count`** says how many declaring types the cap left out. It counts types, not
methods; the methods they contain are `method_count` minus the sum of the listed counts.

### 3.4 Worked example

A heavily used field shows the digest at work. `com.example.money.Money.ZERO`
(id `4732`), declared `public static final Money ZERO`, is read by 37
methods in 14 types and written once, by `Money`'s static initializer:

| Declaring type | Readers |
|----------------|--------:|
| `Invoice` (`4711`) | 8 |
| `CachedLedger` (`4717`) | 6 |
| `Invoice$Line` (`4712`) | 5 |
| seven types in `com.example.money`, `4750` – `4756` | 3, 3, 2, 2, 1, 1, 1 |
| four further types in `com.example.money`, beyond the cap | 2, 1, 1, 1 |

The listed ten types account for 32 readers, the four others for 5; together they make
the 37 of `method_count`. The sample is ordered by declaring type FQN, so it holds the
six readers in `com.example.billing.CachedLedger` (`4741` – `4746`) and then the first
four in `com.example.billing.Invoice`, by member key. The digest is:

```json
"read_access": {
  "method_count": 37,
  "methods_sample": [4741, 4742, 4743, 4744, 4745, 4746, 4715, 4714, 4718, 4719],
  "sample_truncated": true,
  "by_declaring_type": [
    { "type": 4711, "count": 8 }, { "type": 4717, "count": 6 }, { "type": 4712, "count": 5 },
    { "type": 4750, "count": 3 }, { "type": 4751, "count": 3 }, { "type": 4752, "count": 2 },
    { "type": 4753, "count": 2 }, { "type": 4754, "count": 1 }, { "type": 4755, "count": 1 },
    { "type": 4756, "count": 1 }
  ],
  "others_count": 4
},
"write_access": {
  "method_count": 1,
  "methods_sample": [4733],
  "sample_truncated": false,
  "by_declaring_type": [ { "type": 4730, "count": 1 } ]
}
```

`ZERO` is reported with `is_constant: true`. It is not a compile-time constant, so its
reads remain in the bytecode and are counted.

---

## 4. Response

### 4.1 Example

`Invoice` declares `private final Ledger ledger`, assigned in the constructor and read
by `book()` and `cancel()`. The response for the field (id `4716`):

```json
{
  "nodes": {
    "4716": { "name": "ledger", "qualified_name": "com.example.billing.Invoice#com.example.billing.Ledger ledger", "kind": "java.field" },
    "4711": { "name": "Invoice", "qualified_name": "com.example.billing.Invoice", "kind": "java.class" },
    "4713": { "name": "Ledger", "qualified_name": "com.example.billing.Ledger", "kind": "java.interface" },
    "4718": { "name": "book", "qualified_name": "com.example.billing.Invoice#void book()", "kind": "java.method" },
    "4719": { "name": "cancel", "qualified_name": "com.example.billing.Invoice#void cancel()", "kind": "java.method" },
    "4714": { "name": "<init>", "qualified_name": "com.example.billing.Invoice#void <init>(com.example.billing.Ledger)", "kind": "java.constructor" }
  },
  "field": 4716,
  "declaring_type": 4711,
  "modifiers": ["private", "final"],
  "is_constant": false,
  "type": 4713,
  "type_name": "com.example.billing.Ledger",
  "annotations": [],
  "read_access": {
    "method_count": 2, "methods_sample": [4718, 4719], "sample_truncated": false,
    "by_declaring_type": [ { "type": 4711, "count": 2 } ]
  },
  "write_access": {
    "method_count": 1, "methods_sample": [4714], "sample_truncated": false,
    "by_declaring_type": [ { "type": 4711, "count": 1 } ]
  },
  "location": null
}
```

### 4.2 Fields

| Field | Type | Null? | Description |
|-------|------|-------|-------------|
| `nodes` | map id → slim node | no | Every node the response refers to by id. |
| `field` | id | no | The field itself. |
| `declaring_type` | id | yes | The type that declares the field. |
| `modifiers` | list of strings | no | Visibility first, then storage modifiers (§3.1). |
| `is_constant` | boolean | no | `true` for `static final`. |
| `type` | id | yes | The field's type, when it is a node (§3.1). |
| `type_name` | string | no | The field's type by name, keyword or `unknown`. |
| `annotations` | list of `{type}` | no | Annotation types with a node; `type` is an id. |
| `read_access` | digest | no | Who reads the field (§3.3). |
| `write_access` | digest | no | Who writes the field (§3.3). |
| `location` | object | always `null` | Fields carry no line (§3.1). |

**`nodes`** is the [slim node map](hierograph-mcp-tools.md#63-slim-node-map). It holds
the field, its declaring type, its type and annotation types when they are nodes, every
sampled method with its declaring type, and every type in `by_declaring_type`. Methods
beyond the sample have no entry. Member entries carry the bare `name`, as in
`method_details`; the signature is in `qualified_name` after the `#`. A constructor's
entry has kind `java.constructor`, although `list_children` reports the same id as
`java.method`
([shared spec §3.2](hierograph-mcp-tools.md#32-kinds)).

**`field`** echoes the input so the response can be read on its own; its `nodes` entry
names it.

**`declaring_type`** lets the caller move to the type's declaration (`type_details`) or
siblings (`list_children`). It is `null` only if the owner FQN has no node, which does
not occur for scanned fields.

**`modifiers`** and **`is_constant`** answer "who can see this field?" and "can it
change?". `is_constant` is a convenience for the frequent `static final` check (§3.1).

**`type`** and **`type_name`** are separate because not every type is a node.
`type_name` always gives the caller something to read; `type` is there when the caller
can follow it. A caller never passes a `null` `type` to another tool.

**`annotations`** lists annotation types as ids into `nodes`, the slim counterpart of
the `{type}` entries in `type_details` and `method_details`.

**`read_access`** and **`write_access`** are the reason the tool exists (§3.2, §3.3).
They are split because readers depend on the field's value and writers on its
mutability, and a refactoring affects them differently.

**`location`** keeps the key that `method_details` uses, so the two tools share a shape;
for fields it is always `null`.

---

## 5. Errors

| Code | Situation | Extra fields |
|------|-----------|--------------|
| `NODE_NOT_FOUND` | `fieldId` is not the id of a member: unknown ids, and the ids of types, packages and modules | none |
| `WRONG_NODE_KIND` | `fieldId` names a method or constructor | `actual_kind` (`"java.method"` or `"java.constructor"`) |
| `DETAIL_REBUILDING` | the detail snapshot for the live graph is still being written | none |

All three use the nested shape of
[shared spec §8.1](hierograph-mcp-tools.md#81-error-shapes):

```json
{ "error": {
    "code": "WRONG_NODE_KIND",
    "message": "Node 4715 is a 'java.method', not a field. field_details requires a field-kind node.",
    "actual_kind": "java.method",
    "recovery": "To inspect the method itself, use method_details(methodId: 4715)." } }
```

`NODE_NOT_FOUND` recovers with "Look the field up again via list_children on its
declaring type or find_node." and `DETAIL_REBUILDING` with "Retry the call in a
moment."

The tool looks ids up in the index of member ids only, so the id of a type or package is
reported as `NODE_NOT_FOUND` rather than `WRONG_NODE_KIND`. A method id is found in the
index and reported as `WRONG_NODE_KIND`; pass it to `method_details` instead.

`DETAIL_REBUILDING` is returned after a rescan while the Parquet snapshot of the live
graph is still being written. The member id is checked first, so an id that is not a
member is reported as `NODE_NOT_FOUND` even then. Retrying shortly succeeds.

The no-graph response is described in the
[shared spec §8.3](hierograph-mcp-tools.md#83-no-graph-loaded).

---

## 6. Working with the tool

**From a type to a field's usage.** List the type's fields, then ask for one:

```json
{ "tool": "list_children", "arguments": { "nodeId": 4711, "kindFilter": ["java.field"] } }
{ "tool": "field_details", "arguments": { "fieldId": 4716 } }
```

**Judge a visibility change.** Before making a field `private`, read
`by_declaring_type`. If the declaring type is the only entry in both digests, no other
type touches the field directly.

**List every reader.** When `sample_truncated` is `true` and the full list is needed,
ask `incoming_dependencies(fromId = <declaring type id>, toId = <scope id>, detailLevel =
"detail", relationship = "reads_field")` (or `"writes_field"`) and keep the edges whose
`to` is the field's id. The edges carry the source line of each access.

**Resolve an id.** Look up any id from the response in `nodes`. Pass a method id to
`method_details` and a type id to `type_details` (or, for an `external.type` placeholder,
to the dependency tools).

**Account for inlined constants.** A `static final` field of a primitive or `String`
type with `read_access.method_count` `0` may still be used: its value is inlined into
the readers (§3.2). Search the source for its name before deleting it.

---

## 7. Guarantees

1. **Read-only.** The tool changes no state.
2. **One snapshot.** All Parquet facts in a response come from the snapshot of the live
   graph; when that snapshot is not ready the tool returns `DETAIL_REBUILDING` instead of
   reading another.
3. **Exact counts.** `method_count` and every `count` in `by_declaring_type` cover all
   accessing methods in the snapshot, regardless of the sample and the cap.
4. **Bounded size.** Each digest holds at most 10 sampled methods and 10 declaring
   types, so the response size does not grow with the field's popularity.
5. **Resolvable ids.** Every id in `field`, `annotations`, `methods_sample` and
   `by_declaring_type`, and `type` and `declaring_type` when not `null`, has an entry in
   `nodes`.
6. **Deterministic.** For the same graph and `fieldId`, the response is the same.
