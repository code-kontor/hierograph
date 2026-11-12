# Hierograph Tool `method_details` — Specification

`method_details` is Hierograph's **signature view of a single method or constructor**.
Given a member id, it returns the method's modifiers, return type, parameters with
names and types, the exceptions it declares and the ones its body throws, its
annotations, the method it overrides and the line where its code starts. A language
model calls it when it has found a method and needs its full contract before reading or
changing code that uses it.

This document explains why the tool exists, what it takes, where each fact comes from,
what it returns and how to use it. The concepts all tools share (node kinds, NodeRefs,
error shapes) are defined in the [MCP tools specification](hierograph-mcp-tools.md).

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

`list_children` on a type returns its methods as minimal NodeRefs named by signature:
`com.example.money.Money total(java.lang.String)`. The signature gives the erased
types, but not the parameter names, the modifiers, the `throws` clause, the
annotations or which method is being overridden. Those are what a model needs to call
a method correctly, to judge whether a change to it is safe, or to explain what it
does. `method_details` returns them in one call.

The tool has three design goals:

**One call, the whole contract.** Everything the declaration says about a method comes
back together, so the caller does not assemble a signature from several dependency
queries.

**Every type is a reference.** Return type, parameter types, exception types,
annotation types and the overridden method come back as references with ids, so the
caller can follow any of them into `type_details`, `method_details` or the dependency
tools. Types that are not nodes in the graph still appear by name (§3.2).

**Contract and behaviour side by side.** `throws` is what the method declares;
`raises` is what its body actually throws. Reporting both lets a caller see an
undeclared runtime exception without reading the body.

### `method_details` and its neighbours

| Question | Tool |
|----------|------|
| Which methods does this type declare? | `list_children(nodeId = <type id>)` |
| What is this method's full signature, and what does it override? | `method_details` |
| Which methods call this one, and from which line? | `incoming_dependencies` with `detailLevel` `"detail"` |
| What does this method call? | `outgoing_dependencies` with `detailLevel` `"detail"` |
| What does the declaring type look like? | `type_details` |

The single-entity response uses inline references, not the
[slim node map](hierograph-mcp-tools.md#63-slim-node-map). A method names few nodes,
and most of them once, so a separate map would add size rather than remove it.

---

## 2. Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `methodId` | integer | yes | Id of a method or constructor, as returned by `list_children`, `find_node` or the detail-level dependency tools. |
| `view` | string | no | Name of a view to answer in. |

**`methodId`** is the member's node id. In the tree, constructors are members of kind
`java.method` named `void <init>(…)`
([shared spec §3.1](hierograph-mcp-tools.md#31-nodes-and-the-containment-tree)), so any
`java.method` id is accepted, constructors and the static initializer `<clinit>`
included. A single id is enough because a member id already distinguishes overloads.

**`view`** names a [view](../../io.hierograph.server.views/spec/hierograph-views.md) to
answer in ([§5 of the MCP tools specification](hierograph-mcp-tools.md#5-parameters)).
The server adds it to the tool's schema and resolves it before the tool runs: an unknown
name is a `VIEW_NOT_FOUND` error, a document that does not parse a `VIEW_INVALID` error,
and a value that is not a string an `INVALID_PARAMETER` error. Without it, or with a
blank value, the tool answers from the hierarchy as scanned. A view changes where nodes
sit, not the facts this tool reports.

---

## 3. Behaviour

### 3.1 Where each fact comes from

The tool resolves `methodId` to the method's **member key**
(`<owner FQN>#<signature>`, see
[member signature and member key](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#33-member-signature-and-member-key))
and reads the method's rows from the Parquet snapshot described in the
[snapshot format specification](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md).

| Response field | Source |
|----------------|--------|
| `method` (`name`, `kind`), `modifiers`, `location` | the method's row in `members.parquet` |
| `parameters` | the method's rows in `parameters.parquet` |
| `return_type` | the `returns` edge in `detail_refs.parquet` |
| `throws` | the `throws` edges in `detail_refs.parquet` |
| `raises` | the `raises` edges in `detail_refs.parquet` |
| `annotations` | the method's `annotated_by` edges in `detail_refs.parquet` |
| `overrides` | the `overrides` edges derived when the graph is loaded |
| `declaring_type` | the owner FQN in the member key, resolved against the graph |

**Kind.** A member named `<init>` is a constructor. The response reports it, like every
method, with kind `java.method`, as the containment tree does, and marks it with
`is_constructor: true`; its `return_type` is the declaring type, the type a constructor
produces.

**Modifiers.** Modifiers are decoded from the
[modifier bits](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#51-modifier-bits)
in a fixed order: one visibility value (`public`, `protected`, `private` or
`package-private`), then any of `static`, `final`, `abstract`, `synchronized`, `native`,
`default`, and last the compiler markers `bridge` and `synthetic`. `default` marks an
interface method with a body that is neither static nor private; `bridge` and
`synthetic` mark methods the compiler generated (bridges for generic and covariant
overrides, lambda bodies, accessors), so a reader can tell them from declared ones.

**Types.** Every type in the response is the erased type, reduced to its element type:
a `List<Money>` parameter reports `java.util.List`, a `Money[]` parameter reports
`com.example.money.Money`. Generic type arguments and array dimensions are visible only
in the member's signature (`method.qualified_name`). Primitives and `void` are reported
as primitive references (§3.2). A method without a `returns` edge reports `void`.

**Parameters.** Parameters are listed in declaration order, starting at position `0`;
the implicit `this` is not a parameter. A parameter's name is taken from the
`MethodParameters` attribute, else from the local-variable table, else it is `arg<n>`
(see
[`parameters.parquet`](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#43-parametersparquet)).
Each parameter's `annotations` lists the annotations on that parameter, each once, from
the `annotations` column of `parameters.parquet`. Annotations with `SOURCE` retention
are not in the class file and never appear.

**Throws and raises.** `throws` lists the types in the method's `throws` clause.
`raises` lists the exception types the body constructs and throws on the spot
(`throw new X(…)`); a rethrown, stored or factory-made exception is not attributed.
Each list holds each type once, in the order the snapshot records them.

**Annotations.** The annotations on the method itself, each once. Annotations with
`SOURCE` retention, such as `@Override`, are not in the class file and never appear.
The Kotlin compiler adds `org.jetbrains.annotations.NotNull` or `Nullable` to
non-private methods with a reference return type, so these appear for Kotlin methods.

**Overrides.** Overriding is a fact about two class files, so the snapshot does not
store it; it is derived when the graph is loaded
([derived facts](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#63-derived-facts)).
A method overrides, in every transitive supertype, the method with the same signature,
or, failing that, the methods with the same name and parameter types (a covariant
return). All of them are reported: a method can override a superclass method and
implement interface methods at once, and each is a contract it fulfils. Overrides are
derived only between types of the scanned code: a method that overrides a method of an
external type (`toString()` of `java.lang.Object`, `compareTo` of
`java.lang.Comparable`) does not list it, because the external type's methods are not
in the snapshot. Constructors, static and private methods never
override.

**Location.** `line_number` is the lowest source line in the method's code: the first
line of its body, not the line of its declaration. For a Java constructor this is
usually the declaration line, because the implicit `super()` call is attributed to it.
`location` is `null` for methods without code (abstract and native methods) and for
classes compiled without line-number debug information (see
[line numbers](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md#34-line-numbers)).

### 3.2 References

Three reference forms appear in the response.

**The method reference** (`method`, `overrides`) has the six fields of a
[minimal NodeRef](hierograph-mcp-tools.md#61-minimal-noderef), filled from the member:

| Field | Value | Example |
|-------|-------|---------|
| `id` | the member's node id | `4715` |
| `name` | the member's signature | `com.example.money.Money total(java.lang.String)`, `void <init>()` |
| `qualified_name` | the member key | `com.example.billing.Invoice#com.example.money.Money total(java.lang.String)` |
| `kind` | always `java.method`, constructors included | `java.method` |
| `parent_id`, `parent_kind` | the declaring type's id and kind | `4711`, `java.class` |

`name` and `kind` match what `list_children` and every other tool report for the same id,
so a reference reads the same wherever it appears.

**A type reference** (`declaring_type`, `return_type`, parameter types, `throws`,
`raises`, annotation types) has `id`, `name`, `qualified_name` and `kind`:

| Referenced type | `id` | `kind` | Example |
|-----------------|------|--------|---------|
| a type in the scanned code | its node id | its kind | `com.example.money.Money` |
| an external type with a placeholder node | the placeholder's id | `external.type` | `java.math.BigDecimal` |
| an external type without a node | `null` | `external.type` | `java.lang.String`, `java.lang.IllegalStateException` |

The
[external-type filter](../../../hierograph-graphstore/io.hierograph.graphstore.jvm/spec/hierograph-graphstore.md#6-external-types-and-placeholders)
creates no nodes for ubiquitous names (`java.lang.*`, `kotlin.*`, the JetBrains
nullability annotations). The snapshot still names them, so the tool reports them with
`id: null` and `name` set to the part of the FQN after the last dot. Without this
fallback, every `String` parameter would vanish from the signature.

**A primitive reference** stands for a primitive or `void`:
`{"id": null, "name": "int", "qualified_name": "int", "kind": "java.primitive"}`.
Primitives are not nodes and cannot be passed to other tools.

### 3.3 Worked example

In

```java
package com.example.billing;

public final class Invoice implements Comparable<Invoice>, java.io.Serializable {
    …
    public Money total(String currency) {                                       // line 18
        if (lines.isEmpty()) throw new IllegalStateException("empty invoice");  // line 19
        Money sum = Money.zero(currency);                                       // line 20
        for (Line line : lines) sum = sum.add(line.amount());                   // line 21
        return sum;                                                             // line 22
    }

    @Override
    public int compareTo(Invoice other) { … }
}
```

`total` has the member key
`com.example.billing.Invoice#com.example.money.Money total(java.lang.String)`. Its
return type `Money` is a record in the scanned code and comes back with id `4730`. Its
parameter type `java.lang.String` has no node and comes back with `id: null`. It
declares no exceptions, so `throws` is empty, but its body throws a freshly built
`IllegalStateException`, so `raises` lists it. Its code starts at line `19`, which is
what `location` reports.

`compareTo(Invoice)` reports `overrides: []` and `annotations: []`: `Comparable` is not
scanned, so no override is derived, and `@Override` is not in the class file.

---

## 4. Response

### 4.1 Example

The response for `total` (id `4715`):

```json
{
  "method": {
    "id": 4715, "name": "com.example.money.Money total(java.lang.String)",
    "qualified_name": "com.example.billing.Invoice#com.example.money.Money total(java.lang.String)",
    "kind": "java.method", "parent_id": 4711, "parent_kind": "java.class"
  },
  "declaring_type": { "id": 4711, "name": "Invoice", "qualified_name": "com.example.billing.Invoice", "kind": "java.class" },
  "modifiers": ["public"],
  "is_constructor": false,
  "return_type": { "id": 4730, "name": "Money", "qualified_name": "com.example.money.Money", "kind": "java.record" },
  "parameters": [
    { "position": 0, "name": "currency",
      "type": { "id": null, "name": "String", "qualified_name": "java.lang.String", "kind": "external.type" },
      "annotations": [] }
  ],
  "throws": [],
  "raises": [
    { "id": null, "name": "IllegalStateException", "qualified_name": "java.lang.IllegalStateException", "kind": "external.type" }
  ],
  "annotations": [],
  "overrides": [],
  "location": { "line_number": 19 }
}
```

A method that overrides methods in the scanned code names each of them, sorted by
member key. For `CachedLedger.post` (id `4723`), which implements `Ledger.post`:

```json
"overrides": [
  { "id": 4722, "name": "void post(com.example.billing.Invoice)",
    "qualified_name": "com.example.billing.Ledger#void post(com.example.billing.Invoice)",
    "kind": "java.method", "parent_id": 4713, "parent_kind": "java.interface" }
]
```

### 4.2 Fields

| Field | Type | Null? | Description |
|-------|------|-------|-------------|
| `method` | method reference | no | The method itself (§3.2). |
| `declaring_type` | type reference | no | The type that declares the method. |
| `modifiers` | list of strings | no | Visibility first, then storage modifiers (§3.1). |
| `is_constructor` | boolean | no | `true` for `<init>`. |
| `return_type` | type or primitive reference | no | The erased return type; the declaring type for a constructor. |
| `parameters` | list of objects | no | `{position, name, type, annotations}` per parameter, in order. |
| `throws` | list of type references | no | The declared `throws` clause. |
| `raises` | list of type references | no | Exceptions the body throws on the spot. |
| `annotations` | list of `{type}` | no | Annotations on the method. |
| `overrides` | list of method references | no | The overridden methods in the scanned code, sorted by member key. |
| `location` | object | yes | `{line_number}`, the first line of the method's code. |

**`method`** echoes the input with its signature and member key, so the response can be
read on its own. Its `parent_id` is the declaring type's id.

**`declaring_type`** lets the caller move from a method to its type's declaration
(`type_details`) or siblings (`list_children`) without parsing the member key.

**`modifiers`** answers "who can call this?" and "can it be overridden?". `modifiers`
always holds at least the visibility.

**`is_constructor`** duplicates `kind` as a boolean so a caller can test it directly.

**`return_type`** is never `null`: a method without a return value returns the primitive
`void`, and a constructor returns its declaring type.

**`parameters`** gives names as well as types, because a model that writes a call needs
the names to match arguments to parameters. `position` is explicit so the order
survives any re-sorting by the client. `annotations` holds `{type}` entries like the
method's own (§3.1).

**`throws`** and **`raises`** are separate because they answer different questions:
the contract a caller must handle, and what the method really throws (§3.1).

**`annotations`** wraps each type reference in `{"type": …}`, the same entry shape as
`type_details` and `field_details`, so annotation lists read alike across the three
tools.

**`overrides`** links an implementation to the declaration it fulfils, which is the
first step of any question about polymorphic behaviour. It is a list because one method
can fulfil several declarations; it is empty when the method overrides nothing in the
scanned code (§3.1).

**`location`** gives the caller a line to open. Together with `type_details`'
`location.source_file` it locates the method in its source file.

---

## 5. Errors

| Code | Situation | Extra fields |
|------|-----------|--------------|
| `NODE_NOT_FOUND` | `methodId` names no node | none |
| `WRONG_NODE_KIND` | `methodId` names a type, package or module | `actual_kind`; the recovery names `type_details` and `list_children` for a type, `list_descendants` otherwise |
| `WRONG_NODE_KIND` | `methodId` names a field | `actual_kind` (`"java.field"`) |
| `DETAIL_REBUILDING` | the detail snapshot for the live graph is still being written | none |

All three use the nested shape of
[shared spec §8.1](hierograph-mcp-tools.md#81-error-shapes):

```json
{ "error": {
    "code": "WRONG_NODE_KIND",
    "message": "Node 4711 is a 'java.class', not a method. method_details requires a method-kind node.",
    "actual_kind": "java.class",
    "recovery": "To inspect the type, use type_details(typeId: 4711); to find its methods, use list_children(nodeId: 4711)." } }
```

`WRONG_NODE_KIND` recovers with "To inspect the field itself, use
field_details(fieldId: …)." and `DETAIL_REBUILDING` with "Retry the call in a
moment."

An id that names any other node is reported as `WRONG_NODE_KIND` with the tool to use
instead: a field id points to `field_details`, a type id to `type_details` and
`list_children`, a package or module id to `list_descendants`. Only an id that names no
node at all is `NODE_NOT_FOUND`, which recovers with "Look the method up again via
list_children on its declaring type or find_node."

`DETAIL_REBUILDING` is returned after a rescan while the Parquet snapshot of the live
graph is still being written. The id is checked first, so an id that is not a method
is reported as such even then. Retrying shortly succeeds.

The no-graph response is described in the
[shared spec §8.3](hierograph-mcp-tools.md#83-no-graph-loaded).

---

## 6. Working with the tool

**From a type to a method's signature.** List the type's members, then ask for one:

```json
{ "tool": "list_children",  "arguments": { "nodeId": 4711, "kindFilter": ["java.method"], "namePattern": "total" } }
{ "tool": "method_details", "arguments": { "methodId": 4715 } }
```

**Follow a type in the signature.** Pass the `id` from `return_type`, a parameter's
`type`, `throws` or `raises` to `type_details` when its kind is one of the five Java
type kinds. An `external.type` id is a placeholder: `type_details` rejects it, but the
dependency tools accept it. A `null` id names a type outside the graph by its
`qualified_name`.

**Follow an override up.** Pass an `overrides[].id` back to `method_details` until
`overrides` is empty. The last method reached is the topmost declaration in the scanned
code.

**Find the callers.** `method_details` describes the method, not its use. For "who calls
`total`?", ask `incoming_dependencies(fromId = 4711, toId = <scope id>, detailLevel =
"detail", relationship = "calls")` and keep the edges whose `to` is `4715`.

**Read the body.** Open the file named by `type_details`' `location.source_file` at
`location.line_number`. The line is the first line of code, so the declaration is a
line or two above it.

---

## 7. Guarantees

1. **Read-only.** The tool changes no state.
2. **One snapshot.** All Parquet facts in a response come from the snapshot of the live
   graph; when that snapshot is not ready the tool returns `DETAIL_REBUILDING` instead of
   reading another.
3. **No duplicates.** Each type appears at most once in `throws`, in `raises` and in
   `annotations`.
4. **Complete signature.** Every parameter is listed, in order, with a name and a type;
   the return type is always present.
5. **Ids are inputs.** Every non-null `id` in the response is a node id that other tools
   accept. A `null` id is never a valid input.
6. **Deterministic.** For the same graph and `methodId`, the response is the same.
