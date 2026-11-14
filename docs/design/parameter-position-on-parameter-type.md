# Parameter Positions on `parameter_type` Dependencies — Design

Issue: [#3](https://github.com/code-kontor/hierograph/issues/3) ·
Base: branch `0.3.0` ·
Status: Part A implemented (branch `wuetherich/parameter-positions-on-parameter-type`,
not yet merged); Part B proposed ·
Affects: scanner, snapshot format (version 3), `outgoing_dependencies`,
`incoming_dependencies`

This document proposes two changes to Hierograph's detail level, so that a
positional-parameter question such as *"which methods take `Money` as their first
parameter?"* is answered by one query instead of hundreds:

- **Part A: parameter positions.** Every `parameter_type` and
  `parameter_annotated_by` dependency records the position and name of the
  parameter it comes from. The detail-level tools return both and accept a
  `parameterPosition` filter.
- **Part B: the open form at detail level.** `incoming_dependencies` and
  `outgoing_dependencies` accept an omitted `toId` at `detailLevel = "detail"`, as
  they already do at the type level.

The two parts are independent. Part A alone reduces the query to one call per
candidate type. Together, the two parts reduce it to one call.

---

## Contents

1. [The problem](#1-the-problem)
2. [Where the information is today](#2-where-the-information-is-today)
3. [Part A: parameter positions](#3-part-a-parameter-positions)
4. [Part B: the open form at detail level](#4-part-b-the-open-form-at-detail-level)
5. [The motivating query, before and after](#5-the-motivating-query-before-and-after)
6. [Edge cases](#6-edge-cases)
7. [Compatibility](#7-compatibility)
8. [Implementation](#8-implementation)
9. [Tests](#9-tests)
10. [Decisions](#10-decisions)

---

## 1. The problem

A detail-level `parameter_type` dependency says that a method has a parameter of
some type. It does not say *which* parameter. Questions about parameter positions
are common in API analysis. Some examples:

- Which methods take a `Request` type as their first parameter? These are the
  handler-shaped methods.
- Which constructors take a `Clock` anywhere other than last?
- Which methods put `@Valid` on their first parameter?

Today, such a question takes three steps:

1. A type-level `incoming_dependencies(fromId = X)` call returns the candidate types.
   This step is cheap and complete.
2. A detail-level `incoming_dependencies(fromId = X, toId = <candidate>,
   relationship = "parameter_type")` call per candidate returns the methods that
   have a parameter of type `X`.
3. A `method_details` call per method reads the `parameters` array and checks
   whether `X` is at position 0.

In the case that motivated the issue, step 1 returned 66 candidate types. Steps 2
and 3 then took about 300 tool calls, spread over four parallel agents, to answer
one structural question. Step 3 is where most of the calls go, and it exists only
because the dependency row does not carry the parameter position.

## 2. Where the information is today

On `0.3.0`, every dependency is one `Dependency` fact (module `…jvm.archive`), stored
as one row of `dependencies.parquet`. The type-level aggregate is rolled up from these
rows at load. The scanner (`ClassScanner`, method visitor `visitEnd`) already emits
one `parameter_type` fact per parameter and one `parameter_annotated_by` fact per
annotation on a parameter:

```kotlin
// One ParameterFacts per parameter: position, name, type, annotations.
parameters = parameters(isStatic),
// ...
// One parameter_type fact per parameter, but without its position.
argumentTypes.forEach { emitType(key, it, DependencyKind.PARAMETER_TYPE, minLine) }
// One parameter_annotated_by fact per annotation on a parameter, without its position.
parameterAnnotations.values.flatten().forEach { emitType(key, it, DependencyKind.PARAMETER_ANNOTATED_BY, null) }
```

The snapshot stores the two sides separately:

| Table | Holds | Has the position? |
|-------|-------|-------------------|
| `parameters.parquet` | one row per parameter: `method_key`, `index`, `name`, `type_fqn`, `annotations` | yes |
| `dependencies.parquet` | one row per fact: `from_key`, `from_type`, `to_key`, `to_type`, `relationship`, `line` | no |

The tools read them separately. `method_details` reads `parameters.parquet` for one
method and returns `parameters[].position` and `parameters[].name`. The detail-level
dependency tools read `dependencies.parquet` through `DetailQueryEngine` and never
see the parameters.

So a method with two parameters of type `Money`, such as `transfer(Money from, Money
to)`, has two `parameter_type` rows to `Money` that are **identical**: same source,
target, relationship and line. Nothing tells them apart, and nothing says which
parameter either one is.

## 3. Part A: parameter positions

### 3.1 Behaviour

After the change, each `parameter_type` and `parameter_annotated_by` fact carries
the 0-based position and the name of its parameter. The number of facts is
unchanged, so type-level weights are unchanged.

Consider this class:

```java
package com.example.billing;

public class Ledger {
    public void post(Money amount) { … }                            // line 21
    public void transfer(Money from, Money to, String memo) { … }   // line 30
    public void adjust(@NonNull Account account, Money delta) { … } // line 40
}
```

Before the change, `dependencies.parquet` holds these parameter rows for `Ledger`:

| `from_key` | `to_type` | `relationship` | `line` |
|------------|-----------|----------------|--------|
| `…Ledger#void post(…Money)` | `…Money` | `parameter_type` | 21 |
| `…Ledger#void transfer(…)` | `…Money` | `parameter_type` | 30 |
| `…Ledger#void transfer(…)` | `…Money` | `parameter_type` | 30 |
| `…Ledger#void transfer(…)` | `java.lang.String` | `parameter_type` | 30 |
| `…Ledger#void adjust(…)` | `…Account` | `parameter_type` | 40 |
| `…Ledger#void adjust(…)` | `…Money` | `parameter_type` | 40 |
| `…Ledger#void adjust(…)` | `…NonNull` | `parameter_annotated_by` | — |

After the change, the same rows carry their parameter:

| `from_key` | `to_type` | `relationship` | `line` | `parameter_index` | `parameter_name` |
|------------|-----------|----------------|--------|-------------------|------------------|
| `…Ledger#void post(…Money)` | `…Money` | `parameter_type` | 21 | 0 | `amount` |
| `…Ledger#void transfer(…)` | `…Money` | `parameter_type` | 30 | 0 | `from` |
| `…Ledger#void transfer(…)` | `…Money` | `parameter_type` | 30 | 1 | `to` |
| `…Ledger#void transfer(…)` | `java.lang.String` | `parameter_type` | 30 | 2 | `memo` |
| `…Ledger#void adjust(…)` | `…Account` | `parameter_type` | 40 | 0 | `account` |
| `…Ledger#void adjust(…)` | `…Money` | `parameter_type` | 40 | 1 | `delta` |
| `…Ledger#void adjust(…)` | `…NonNull` | `parameter_annotated_by` | — | 0 | `account` |

The two `transfer` rows to `Money` now differ by their position. Every other
relationship keeps `null` in both new columns.

### 3.2 Storage: two optional columns in `dependencies`

`dependencies.parquet` gains two columns:

| Column | Type | Null? | Description |
|--------|------|-------|-------------|
| `parameter_index` | int32 | yes | 0-based position of the parameter, for `parameter_type` and `parameter_annotated_by`; `null` for every other relationship. |
| `parameter_name` | string | yes | The parameter's name, as in `parameters.parquet`. |

The column name `parameter_index` follows `parameters.parquet`, whose position
column is named `index`. The values are the same, so a reader can join the two
tables on (`from_key` = `method_key`, `parameter_index` = `index`).

**Why store the name instead of joining it.** The name is already in
`parameters.parquet`, so the tools could look it up. Storing it in the dependency
row is still the better choice, for three reasons:

- **The row stays self-contained.** The format's design principle is that every row
  is self-contained. A dependency row that needs a second table to be readable
  breaks that principle.
- **Joins at query time are expensive.** `parameters.parquet` is sorted by
  `method_key`, not by type, so a join for one page of edges means one scoped read
  per distinct method on the page.
- **The cost is small.** Both columns are `null` for every non-parameter row, and
  Parquet stores a null as a bit in the definition levels. The whole change adds 3%
  to the snapshot size (§3.8). In memory, the name is the interned string that
  `ParameterFacts` already holds.

`Dependency` gains the two matching fields and the shared sort order:

```kotlin
data class Dependency(
    val fromKey: String,
    val fromType: String,
    val toKey: String,
    val toType: String,
    val kind: DependencyKind,
    val line: Int? = null,
    val fromArtifact: String? = null,
    val parameterIndex: Int? = null,
    val parameterName: String? = null,
) {
    companion object {
        /** The total order of detail facts; see §3.4. */
        val ORDER: Comparator<Dependency> = compareBy(
            { it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.kind.value },
            { it.line ?: -1 }, { it.parameterIndex ?: -1 },
        )
    }
}
```

Both fields are optional with `null` defaults, so all existing constructor calls,
including the load-derived `overrides` and resolved `calls` facts, compile unchanged.

The format version goes from 2 to 3 (§7).

### 3.3 Scanner

The scanner builds the `ParameterFacts` list once and derives the `parameter_type`
facts from it, so `members`/`parameters` and `dependencies` cannot disagree:

```kotlin
val params = parameters(isStatic)
members += MemberFacts(..., parameters = params, ...)

params.forEach { p ->
    dependencies += Dependency(key, fqn, p.typeFqn, p.typeFqn, DependencyKind.PARAMETER_TYPE, minLine, artifactKey,
        parameterIndex = p.index, parameterName = p.name)
}
parameterAnnotations.forEach { (i, types) ->
    types.forEach {
        val target = typeTarget(it)
        dependencies += Dependency(key, fqn, target, target, DependencyKind.PARAMETER_ANNOTATED_BY, null, artifactKey,
            parameterIndex = i, parameterName = params.getOrNull(i)?.name)
    }
}
```

The `parameter_annotated_by` facts are emitted from the same per-parameter
annotation lists as before, not from the de-duplicated `ParameterFacts.annotations`.
The number of facts, and therefore every type-level weight, stays exactly the same.

### 3.4 Sort order

Before the change, three places sorted detail facts, each with its own comparator:

- `ParquetWriter.writeDependencies` sorted by (`from_type`, `from_key`, `to_type`,
  `to_key`, `relationship`).
- `DetailQueryEngine` sorted by the same keys plus `line`.
- `ParquetDetailDependencies.ROW_ORDER` sorted by the same keys plus `line`.

Facts for two parameters of the same type tie on all of these keys, so their order
would depend on the input order. The three comparators are replaced by one shared
order, `Dependency.ORDER`: (`from_type`, `from_key`, `to_type`, `to_key`,
`relationship`, `line`, `parameter_index`), with nulls first. The writer now also
sorts by `line`, so the file, the engine and the tool use the same order. The file is
still sorted by `from_type` first, so row-group skipping is unaffected.

Because the order puts the target type before the relationship, the
`parameter_annotated_by` row of a parameter does not sort next to its
`parameter_type` row. A caller that wants a method's parameters in order sorts by
`parameter.position`.

### 3.5 Reading

`ParquetReader.readDependencies` reads both columns. Every format-3 file has them,
and the format check rejects older files before any row is read (§7).

The position filter is applied in memory by `DetailQueryEngine`, after the single
push-down predicate. The reader explains why it pushes down only one predicate
(hardwood mis-prunes pages when predicates are AND-ed). The parameter rows of a
scoped read are few, so an in-memory filter costs nothing measurable.

### 3.6 Tool API

Both `outgoing_dependencies` and `incoming_dependencies` gain one parameter:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `parameterPosition` | integer ≥ 0 | no | Detail level only: keep the parameter rows of the parameter at this 0-based position. |

The filter has these rules:

- It is valid only at `detailLevel = "detail"`. At the type level it is an
  `INVALID_PARAMETER` error, like `relationship`.
- With `relationship = "parameter_type"` or `"parameter_annotated_by"`, it keeps
  the rows of that relationship whose position matches.
- Without `relationship`, it keeps the rows of **both** parameter relationships
  whose position matches. A position filter selects parameter rows by definition,
  so an implicit restriction is the only meaningful reading.
- With any other relationship, it is an `INVALID_PARAMETER` error, because no row
  of that relationship has a position.
- A negative value is an `INVALID_PARAMETER` error.
- The load-derived facts (`overrides`, resolved calls) have no position, so a
  position query skips deriving them.
- The filter value is part of the cursor's query hash, so a cursor issued for
  position 0 is rejected for position 1.

Each parameter row in `edges[]` gains a `parameter` object. The field names match
`method_details`, which returns `parameters[].position` and `parameters[].name`:

```json
{ "from": 4716, "from_parent": 4713, "to": 4730,
  "relationship": "parameter_type",
  "parameter": { "position": 0, "name": "amount" },
  "location": { "line_number": 21 } }
```

Rows of other relationships have no `parameter` field. That keeps the response
size of non-parameter queries unchanged.

`graph_overview` describes the two relationships as one edge per parameter (per
annotation on a parameter), with `parameter={position,name}`.

### 3.7 Example

The question is: which methods of `com.example.billing` take `Money` as their
first parameter? `Money` is node 4730, `billing` is node 4702, and the request is:

```
incoming_dependencies(fromId = 4730, toId = 4702,
                      detailLevel = "detail",
                      relationship = "parameter_type",
                      parameterPosition = 0)
```

The response (`nodes` abbreviated) is:

```json
{
  "nodes": {
    "4702": { "name": "billing", "qualified_name": "com.example.billing", "kind": "java.package" },
    "4713": { "name": "Ledger", "qualified_name": "com.example.billing.Ledger", "kind": "java.class" },
    "4716": { "name": "void post(com.example.money.Money)", "kind": "java.method", "…": "…" },
    "4717": { "name": "void transfer(com.example.money.Money,com.example.money.Money,java.lang.String)", "kind": "java.method", "…": "…" },
    "4730": { "name": "Money", "qualified_name": "com.example.money.Money", "kind": "java.record" }
  },
  "from_scope": 4702,
  "to_scope": 4730,
  "edges": [
    { "from": 4716, "from_parent": 4713, "to": 4730, "relationship": "parameter_type",
      "parameter": { "position": 0, "name": "amount" }, "location": { "line_number": 21 } },
    { "from": 4717, "from_parent": 4713, "to": 4730, "relationship": "parameter_type",
      "parameter": { "position": 0, "name": "from" }, "location": { "line_number": 30 } }
  ],
  "summary": {
    "total": 2, "returned": 2, "truncated": false, "inherited": 0,
    "by_relationship": { "parameter_type": 2 },
    "by_source_type": [ { "type": 4713, "edge_count": 2 } ],
    "by_source_nodes": [ "…" ],
    "by_target_nodes": [ "…" ]
  }
}
```

`adjust(Account, Money)` is not listed, because its `Money` parameter is at
position 1. Without `parameterPosition`, the same query returns four rows: `post`
at 0, `transfer` at 0 and 1, and `adjust` at 1.

The summaries need no new fields. `summary.total` is the number of matching
parameters, and `by_source_type` ranks the types that declare them.

### 3.8 Measured cost

The 21 Spring Framework 7.0.8 jars of the integration-test fixture were scanned
twice, with the `0.3.0` scanner before and after the change. Both scans produce
7,707 types, 74,120 members and 470,523 dependency rows; only the rows' content
differs. The snapshots use the default ZSTD compression.

**On disk:**

| Table | Before (bytes) | After (bytes) | Change |
|-------|---------------:|--------------:|-------:|
| `dependencies.parquet` | 4,690,153 | 4,951,129 | +260,976 (+5.6%) |
| `members.parquet` | 2,446,074 | 2,446,074 | none |
| `parameters.parquet` | 956,749 | 956,749 | none |
| `types.parquet` | 442,671 | 442,671 | none |
| **Whole snapshot** | **8,535,647** | **8,796,623** | **+260,976 (+3.06%)** |

All of the growth is the two new columns. Most of it is `parameter_name`; the
position and the null markers of the non-parameter rows cost little.

**In memory:** the scan result, including every `Dependency`, stays in memory
between builds. A heap histogram of the retained scan result shows:

| | Instances | Bytes | Per instance |
|---|---:|---:|---:|
| `Dependency` before | 470,523 | 18,820,920 | 40 |
| `Dependency` after | 470,523 | 22,585,104 | 48 |

That is +3.76 MB for this snapshot, +20% of the `Dependency` objects themselves.
The fields hold no new strings: names are the interned strings `ParameterFacts`
already holds, and small positions are cached `Integer`s. With compressed object
pointers a `Dependency` was exactly 40 bytes, so any additional field rounds up to
48. A single position field without the name would cost the same 8 bytes.

## 4. Part B: the open form at detail level

### 4.1 Behaviour

At the type level, `toId` is optional: `incoming_dependencies(fromId = X)` lists
everything that depends on `X`, from anywhere. At the detail level, the tools
refuse an omitted `toId` with `INVALID_PARAMETER`. A detail-level question about `X`
therefore always starts with a type-level query that finds the candidates, followed
by one detail query per candidate.

After the change, an omitted `toId` at the detail level means "the whole graph" on
that side:

- `incoming_dependencies(fromId = X, detailLevel = "detail")` lists every member
  reference into `X`.
- `outgoing_dependencies(fromId = X, detailLevel = "detail")` lists every member
  reference out of `X`.

The rules of the type-level open form carry over. In particular, on a package,
references between types of the package itself are included.

### 4.2 Example

The question is: which methods anywhere take `Money` as their first parameter?
One call answers it:

```
incoming_dependencies(fromId = 4730,
                      detailLevel = "detail",
                      relationship = "parameter_type",
                      parameterPosition = 0,
                      limit = 90)
```

`summary.by_source_type` ranks the declaring types over the full result set. When
the count alone answers the question, `limit = 1` returns only the summaries,
which is the same advice the type-level open form gives.

### 4.3 Implementation

`ParquetDetailDependencies` already accepts the root node as a scope
(`resolveNodeOrRoot`). The open form could therefore be implemented by
substituting the root id for the missing `toId`. That would expand the root scope
into every type in the graph and push a predicate with tens of thousands of values
down to Parquet. The implementation instead treats the open side as
*unconstrained*:

| Step | Bounded side | Open side |
|------|--------------|-----------|
| Type set passed to `DetailQueryEngine` | the scope's types (the depending side with its supertypes) | `null`, meaning no constraint |
| Handle check on each row | endpoint type must be in the scope's handles | endpoint type must merely be known to the GraphStore |
| Member narrowing | as today | none |
| `inherited` marking | as today | never inherited on this side |
| Drill-down summary | as today | drill-down of the root |
| Response scope field | the node id | `null` |

The code changes are the following:

- `DetailQuery.fromTypes` becomes nullable. `ParquetReader.readDependencies` and
  `HierarchicalGraphService.derivedDependencies` already treat `null` as
  "unconstrained".
- `IDetailDependencies.detailDependencies` takes `toId: Long?`. For the incoming
  direction the tool swaps the arguments, so the nullable side becomes `fromId`.
  Both parameters therefore become nullable, with at most one of them `null`.
- The two tools drop the `toId == null` error branch at the detail level. The tool
  descriptions and specifications drop the sentence "toId is required at detail
  level".

**Cost.** The outgoing open form is cheap, because the push-down runs on the
sorted `from_type` column and skips row groups. The incoming open form pushes down
on `to_type`, which is not the sort key, so it reads every row group of every
snapshot. That is the same cost as a type-level open query on a hub type, paid at
the detail level. It is still far cheaper than the 66 calls it replaces, but on
large graphs it can take noticeably longer than a scoped query. The tool
description should say so, as it already advises `limit = 1` for summaries.

## 5. The motivating query, before and after

| | Calls for "methods that take X as their first parameter" | Notes |
|---|---|---|
| Today | 1 + N + M (66 candidates: about 300) | type-level candidates, a detail query per candidate, `method_details` per method |
| Part A only | 1 + N (66 candidates: 67) | `parameterPosition = 0` replaces every `method_details` call |
| Parts A and B | 1 | the open form replaces the candidate step |

## 6. Edge cases

| Case | Behaviour | Why |
|------|-----------|-----|
| Two parameters of the same type | two rows, positions differ | before the change the two rows were identical |
| Array parameter `Money[] items` | `to_type` is `Money`, as before | targets are element types; the exact spelling is in the member signature |
| Varargs `Money... items` | like an array; the last position | varargs compile to an array parameter |
| Primitive parameter `int count` | a row to `int`, as before | primitives are recorded so a member's shape is complete |
| Generic parameter `List<Money>` | a `parameter_type` row to `java.util.List` at the parameter's position | `parameter_type` uses the erased type; `type_argument` facts for `Money` carry no position |
| Parameter name not compiled in | `arg<i>`, as in `parameters.parquet` | the name comes from the same `ParameterFacts` |
| Inner-class constructor | the outer instance is at position 0 | positions follow the bytecode descriptor, as `method_details` already does (§10) |
| Enum constructor | the synthetic `name` and `ordinal` are at positions 0 and 1 | same reason |
| Kotlin `foo$default` | the synthetic mask and marker parameters are at the end | same reason; the declared parameters keep their positions |
| Annotation on a parameter the descriptor lacks | position set, name `null` | the annotation's parameter index has no `ParameterFacts` |
| Bridge method | rows like any method | bridges are scanned as members |
| Load-derived facts (`overrides`, resolved `calls`) | no parameter fields | they are not parameter relationships |

## 7. Compatibility

**Snapshots.** The format version goes from 2 to 3. A format-2 snapshot has no
positions, so a position query would silently return no rows for it. The existing
format check already handles this case: it rejects an artifact of an older version
as stale, with a message that says to rescan it. A format-2 snapshot is therefore
rescanned instead of being read wrongly. A test pins the rejection of format 2.

**Weights.** The number of facts is unchanged, so every type-level weight and every
aggregate rolled up from `dependencies.parquet` stays the same.

**Responses.** The new `parameter` field is additive. Clients that do not know it
ignore it.

**Memory.** The retained scan result grows by 8 bytes per dependency (§3.8).

## 8. Implementation

Part A is implemented as follows:

| Module | File | Change |
|--------|------|--------|
| archive | `model/Dependency.kt` | add `parameterIndex`, `parameterName` and the shared `Dependency.ORDER` |
| archive | `ParquetSchema.kt` | add `Dependencies.PARAMETER_INDEX`, `PARAMETER_NAME`; extend `dependenciesSchema()`; `FORMAT_VERSION` 2 → 3 |
| archive | `ParquetWriter.kt` | write both columns; sort by `Dependency.ORDER` |
| archive | `ParquetReader.kt` | read both columns |
| store | `detail/DetailQueryEngine.kt` | add `parameterIndex` to `DetailQuery`; filter in memory; sort by `Dependency.ORDER`; carry the fields in `reverseEdges` |
| scanner | `ClassScanner.kt` | attach position and name to the `parameter_type` facts (from `ParameterFacts`) and the `parameter_annotated_by` facts |
| server | `detail/IDetailDependencies.kt` | new trailing `parameterPosition: Int? = null` argument |
| server | `detail/ParquetDetailDependencies.kt` | validate and hash `parameterPosition`; skip the load-derived facts when it is set; emit `edges[].parameter`; sort by `Dependency.ORDER` instead of `ROW_ORDER` |
| server | `dependencyanalysis/IncomingDependenciesTool.kt`, `OutgoingDependenciesTool.kt` | the new trailing `@ToolParam`, type-level rejection, descriptions |
| server | `navigation/GraphOverviewTool.kt` | relationship descriptions |
| specs | `hierograph-graphstore-format.md` (§4.3, §4.4, §6.5, §7), `hierograph-graphstore-scanner.md`, both tool specs | document the columns, descriptor positions, format 3, the filter and the field |

Part B (proposed):

| Module | File | Change |
|--------|------|--------|
| store | `detail/DetailQueryEngine.kt` | `DetailQuery.fromTypes` nullable |
| server | `detail/IDetailDependencies.kt` | nullable scope ids |
| server | `detail/ParquetDetailDependencies.kt` | unconstrained open side (§4.3) |
| server | both dependency tools | drop the detail-level `toId` error; update descriptions |
| specs | both tool specs | the open form at detail level; the cost note |

## 9. Tests

The Part A tests are implemented and pass. The full reactor build and all 112
integration tests pass.

**Scanner** (`BytecodeScannerTest`, new fixture class `fix.Ledger`):

- `transfer(Circle from, Circle to, @Deprecated String memo)` yields three
  `parameter_type` facts with positions 0, 1 and 2 and their names, and one
  `parameter_annotated_by` fact at position 2.
- No fact of any other kind carries a parameter.
- For every method in the fixture, the `parameter_type` facts match its
  `ParameterFacts` in position, name and type.
- The inner-class constructor `Ledger.Entry(String)` has the outer `Ledger` at
  position 0. The enum constructor of `Circle.Color` has `String` and `int` at
  positions 0 and 1.

**Archive** (`ParquetWriterTest`, `ParquetReaderTest`):

- The parameter fields survive a write and read round trip. Rows written out of
  order come back in parameter order.
- An artifact of format 2 is rejected as stale, with the rescan message.

**Engine** (`DetailQueryEngineTest`):

- `parameterIndex` keeps the facts at that position. Without a kind it selects both
  parameter kinds.
- Facts for two parameters of the same type come out in parameter order.

**Tools** (new `DetailParameterPositionTest`; `DetailFixture(withParameterMethod =
true)` adds `Invoice#transfer(Money from, Money to)`. The flag keeps the shared
fixture's type-level totals unchanged for the other tests):

- Parameter edges carry `parameter: {position, name}`, one per parameter. Edges of
  other relationships have no `parameter` field.
- `parameterPosition` filters both tools. Without a relationship it selects only
  parameter edges.
- `INVALID_PARAMETER` is returned at the type level, for a negative position, and
  for a non-parameter relationship.
- A cursor issued for one position is rejected for another.

**Integration** (`DetailEdgeVocabularyIT`, Spring 7.0.8 snapshot): every
`parameter_type` fact has a position and a name, every `parameter_annotated_by` fact
has a position, and no other fact has one. At least one method has two facts to the
same type with different positions, and its facts match its parameters.

**Part B (not yet written):** the detail-level open form returns the same rows as
the union of the per-candidate queries. `DependencyToolsOptionalToIdTest` changes
from expecting an error to expecting results.

## 10. Decisions

Resolved by the Part A implementation:

1. **Filter name: `parameterPosition`.** The issue proposed `parameterIndex`,
   matching the storage column. The tool API uses `parameterPosition` instead, to
   match the `position` field that `method_details` already returns, so that one
   concept has one name in the API. The storage column is `parameter_index`,
   matching `parameters.parquet`.
2. **Descriptor positions.** Positions follow the bytecode descriptor, so synthetic
   parameters (the outer instance, the enum `name` and `ordinal`) occupy positions.
   The format spec previously said that `index` "counts declared parameters only".
   The spec now describes descriptor positions, and a scanner test pins them.
   Skipping synthetic parameters would only work for classes compiled with
   `-parameters`. This can be revisited if callers report confusion.
3. **Old snapshots: bump the format version.** `0.3.0` already rejects stale
   snapshots by format version, so format 3 makes a format-2 snapshot be rescanned
   rather than read without positions.
4. **Store the name in each row.** It costs 3% on disk and 8 bytes per dependency in
   memory, which a position alone would cost as well (§3.8).

Still open:

5. **Ship Part B in the same change?** The parts are independent. Part A is
   self-contained and low-risk. Part B changes the shape of a public tool contract
   (nullable scopes, the `null` scope field) and has the cost caveat of §4.3. The
   recommendation is to ship them as two pull requests, Part A first.
6. **A `parameterName` filter?** The name is stored, so a name filter is cheap to
   add. It is left out until a use case appears, because names depend on compiler
   flags and are less reliable than positions.
