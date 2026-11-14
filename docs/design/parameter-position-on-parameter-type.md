# Parameter Positions on `parameter_type` Dependencies — Design

Issue: [#3](https://github.com/code-kontor/hierograph/issues/3) ·
Status: Part A implemented (branch `wuetherich/expose-parameter-index-position-on-parameter_typ`,
not yet merged); Part B proposed · Affects: scanner, graphstore format,
`outgoing_dependencies`, `incoming_dependencies`

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
8. [Implementation plan](#8-implementation-plan)
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

The scanner already knows each parameter's position at the moment it creates the
`parameter_type` dependency. It discards the position.

**The scanner** (`ClassScanner`, method visitor `visitEnd`) builds two things from
the same `argumentTypes` array:

```kotlin
// One ParameterFacts per parameter: position, name, type, annotations.
parameters = parameters(isStatic),
// ...
// One parameter_type row per *distinct* type — position dropped, duplicates merged.
argumentTypes.map { typeTarget(it) }.distinct().forEach { detail(key, it, "parameter_type", minLine) }
// One parameter_annotated_by row per distinct annotation — position dropped.
parameterAnnotations.values.flatten().map { typeTarget(it) }.distinct()
    .forEach { detail(key, it, "parameter_annotated_by", null) }
```

**The snapshot** stores the two separately:

| Table | Holds | Has the position? |
|-------|-------|-------------------|
| `parameters.parquet` | one row per parameter: `method_key`, `index`, `name`, `type_fqn`, `annotations` | yes |
| `detail_refs.parquet` | one row per dependency: `from_key`, `from_type`, `to_key`, `to_type`, `relationship`, `line` | no |

**The tools** read them separately. `method_details` reads `parameters.parquet` for
one method and returns `parameters[].position` and `parameters[].name`. The
detail-level dependency tools read `detail_refs.parquet` through
`DetailQueryEngine` and never see the parameters.

The `.distinct()` call has a second effect. A method with two parameters of type
`Money`, such as `transfer(Money from, Money to)`, has *one* `parameter_type` row to
`Money`, not two. A position filter on top of the current rows would therefore be
wrong: the row for position 0 would exist, and the row for position 1 would not.

## 3. Part A: parameter positions

### 3.1 Behaviour

After the change, the scanner emits one `parameter_type` row **per parameter**,
not per distinct type. Each row carries the parameter's 0-based position and its
name. Likewise, the scanner emits one `parameter_annotated_by` row per
(parameter, annotation) pair.

Consider this class:

```java
package com.example.billing;

public class Ledger {
    public void post(Money amount) { … }                          // line 21
    public void transfer(Money from, Money to, String memo) { … } // line 30
    public void adjust(@NonNull Account account, Money delta) { … } // line 40
}
```

Today, `detail_refs.parquet` holds these parameter rows for `Ledger`:

| `from_key` | `to_type` | `relationship` | `line` |
|------------|-----------|----------------|--------|
| `…Ledger#void post(…Money)` | `…Money` | `parameter_type` | 21 |
| `…Ledger#void transfer(…Money,…Money,java.lang.String)` | `…Money` | `parameter_type` | 30 |
| `…Ledger#void transfer(…Money,…Money,java.lang.String)` | `java.lang.String` | `parameter_type` | 30 |
| `…Ledger#void adjust(…Account,…Money)` | `…Account` | `parameter_type` | 40 |
| `…Ledger#void adjust(…Account,…Money)` | `…Money` | `parameter_type` | 40 |
| `…Ledger#void adjust(…Account,…Money)` | `…NonNull` | `parameter_annotated_by` | — |

After the change, the table holds these rows:

| `from_key` | `to_type` | `relationship` | `line` | `parameter_index` | `parameter_name` |
|------------|-----------|----------------|--------|-------------------|------------------|
| `…Ledger#void post(…Money)` | `…Money` | `parameter_type` | 21 | 0 | `amount` |
| `…Ledger#void transfer(…)` | `…Money` | `parameter_type` | 30 | 0 | `from` |
| `…Ledger#void transfer(…)` | `…Money` | `parameter_type` | 30 | 1 | `to` |
| `…Ledger#void transfer(…)` | `java.lang.String` | `parameter_type` | 30 | 2 | `memo` |
| `…Ledger#void adjust(…)` | `…Account` | `parameter_type` | 40 | 0 | `account` |
| `…Ledger#void adjust(…)` | `…Money` | `parameter_type` | 40 | 1 | `delta` |
| `…Ledger#void adjust(…)` | `…NonNull` | `parameter_annotated_by` | — | 0 | `account` |

`transfer` now has two rows to `Money`, which differ by their position. Every
other relationship keeps `null` in both new columns.

### 3.2 Storage: two optional columns in `detail_refs`

`detail_refs.parquet` gains two columns:

| Column | Type | Null? | Description |
|--------|------|-------|-------------|
| `parameter_index` | int32 | yes | 0-based position of the parameter, for `parameter_type` and `parameter_annotated_by`; `null` for every other relationship. |
| `parameter_name` | string | yes | The parameter's name, as in `parameters.parquet`; `null` whenever `parameter_index` is `null`. |

The column name `parameter_index` follows `parameters.parquet`, whose position
column is named `index`. The values are the same, so a reader can join the two
tables on (`from_key` = `method_key`, `parameter_index` = `index`).

**Why store the name instead of joining it.** The name is already in
`parameters.parquet`, so the tools could look it up. Storing it in the dependency
row is still the better choice, for three reasons:

- **The row stays self-contained.** The format's design principle is "every row is
  self-contained" (format spec §1). A dependency row that needs a second table to
  be readable breaks that principle.
- **Joins at query time are expensive.** `parameters.parquet` is sorted by
  `method_key`, not by type, so a join for one page of edges means one scoped read
  per distinct method on the page.
- **The cost is small.** Both columns are `null` for every non-parameter row, and
  Parquet stores a null as a bit in the definition levels. On a real snapshot the
  whole change adds 3% to the snapshot size (§3.8).

`DetailRef` gains the two matching fields:

```kotlin
data class DetailRef(
    val fromKey: String,
    val fromType: String,
    val toKey: String,
    val toType: String,
    val relationship: String,
    val line: Int? = null,
    val fromArtifact: String? = null,
    /** 0-based parameter position; set for parameter_type / parameter_annotated_by only. */
    val parameterIndex: Int? = null,
    /** The parameter's name, as in ParameterFacts.name; set exactly when parameterIndex is. */
    val parameterName: String? = null,
) {
    companion object {
        /** The total order of detail rows; see §3.4. */
        val ORDER: Comparator<DetailRef> = compareBy(
            { it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.relationship },
            { it.line ?: -1 }, { it.parameterIndex ?: -1 },
        )
    }
}
```

Both fields are optional with `null` defaults, so all existing constructor calls,
including the load-derived `overrides` and resolved `calls` rows in
`HierarchyResolution`, compile unchanged.

### 3.3 Scanner

The scanner derives both rows from the `ParameterFacts` list it already builds.
One list then feeds `members`/`parameters` and `detail_refs`, so the two tables
cannot disagree:

```kotlin
val params = parameters(isStatic)
members += MemberFacts(..., parameters = params, ...)

detail(key, typeTarget(methodType.returnType), "returns", minLine)
params.forEach { p ->
    detailRefs += DetailRef(key, fqn, p.typeFqn, p.typeFqn, "parameter_type", minLine,
        parameterIndex = p.index, parameterName = p.name)
    p.annotations.forEach { a ->
        detailRefs += DetailRef(key, fqn, a, a, "parameter_annotated_by", null,
            parameterIndex = p.index, parameterName = p.name)
    }
}
```

`ParameterFacts.annotations` is already de-duplicated per parameter, so an
annotation that repeats on one parameter still yields one row.

### 3.4 Sort order

Before the change, three places sorted detail rows, each with its own comparator:

- `ParquetWriter.writeDetailRefs` sorted by (`from_type`, `from_key`, `to_type`,
  `to_key`, `relationship`).
- `DetailQueryEngine` sorted by the same keys plus `line`.
- `ParquetDetailDependencies.ROW_ORDER` sorted by the same keys plus `line`.

Rows for two parameters of the same type tie on all of these keys, so their order
would depend on the input order. The three comparators are therefore replaced by one
shared order, `DetailRef.ORDER`: (`from_type`, `from_key`, `to_type`, `to_key`,
`relationship`, `line`, `parameter_index`), with nulls first. The writer now also
sorts by `line`, so the file, the engine and the tool use the same order. The file
is still sorted by `from_type` first, so row-group skipping is unaffected.

Because the order puts the target type before the relationship, the
`parameter_annotated_by` row of a parameter does not sort next to its
`parameter_type` row. A caller that wants a method's parameters in order sorts by
`parameter.position`.

### 3.5 Reading

`ParquetReader.readDetailRefs` reads the new columns only when the file has them,
following the pattern already used for `types.modifiers` and
`parameters.annotations`:

```kotlin
parameterIndex = if (r.hasColumn(cols.PARAMETER_INDEX)) r.intOrNull(cols.PARAMETER_INDEX) else null,
parameterName = if (r.hasColumn(cols.PARAMETER_NAME)) r.stringOrNull(cols.PARAMETER_NAME) else null,
```

The position filter is applied in memory, after the existing single push-down
predicate. The reader comment explains why it pushes down only one predicate
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

`graph_overview` updates its descriptions of the two relationships, for example:
*"Method has a parameter of this type (one row per parameter; carries its position
and name)"*.

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

### 3.8 Measured size impact

The 21 Spring Framework 7.0.8 jars of the integration-test fixture were scanned
twice, once with the scanner before the change and once after it. The snapshots use
the default ZSTD compression.

| Table | Before (bytes) | After (bytes) | Change |
|-------|---------------:|--------------:|-------:|
| `detail_refs.parquet` | 4,239,252 | 4,495,437 | +256,185 (+6.0%) |
| `members.parquet` | 2,444,375 | 2,444,375 | none |
| `parameters.parquet` | 956,056 | 956,056 | none |
| `refs.parquet` | 590,400 | 590,400 | none |
| `types.parquet` | 442,109 | 442,109 | none |
| **Whole snapshot** | **8,672,192** | **8,928,377** | **+256,185 (+2.95%)** |

The growth has two sources:

- **More rows.** `detail_refs` grows from 389,927 to 394,559 rows (+4,632,
  +1.2%). The added rows are parameters of a type that the method already takes at
  another position. Before the change, `.distinct()` merged them into one row.
- **The two new columns.** Most of the growth comes from `parameter_name`. The
  position and the null markers of the non-parameter rows cost little.

The other tables are unchanged byte for byte. In memory, `DetailRef` objects carry
two more fields, but detail rows are read per query and not kept.

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
| Type set passed to `DetailQueryEngine` | expanded scope (subtree plus supertypes) | `null`, meaning no constraint |
| Handle check on each row | endpoint type must be in the scope's handles | endpoint type must merely be known to the GraphStore |
| Member narrowing | as today | none |
| `inherited` marking | as today | never inherited on this side |
| Drill-down summary | as today | drill-down of the root |
| Response scope field | the node id | `null` |

The code changes are the following:

- `DetailQuery.fromTypes` becomes nullable. `ParquetReader.readDetailRefs` and
  `HierarchicalGraphService.derivedDetailRefs` already treat `null` as
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
| Two parameters of the same type | two rows, positions differ | the point of Part A; today the rows collapse into one |
| Array parameter `Money[] items` | `to_type` is `Money`, as today | targets are element types; the exact spelling is in the member signature |
| Varargs `Money... items` | like an array; the last position | varargs compile to an array parameter |
| Primitive parameter `int count` | a row to `int`, as today | primitives are recorded so a member's shape is complete |
| Generic parameter `List<Money>` | a row to `java.util.List` at the parameter's position | `parameter_type` uses the erased type. Type arguments are counted elsewhere and carry no position |
| Parameter name not compiled in | `arg<i>`, as in `parameters.parquet` | the name comes from the same `ParameterFacts` |
| Inner-class constructor | the outer instance appears at position 0 | positions follow the bytecode descriptor, as `method_details` already does (see §10) |
| Enum constructor | the synthetic `name` and `ordinal` appear at positions 0 and 1 | same reason |
| Kotlin `foo$default` | the synthetic mask and marker parameters appear at the end | same reason; the declared parameters keep their positions |
| Bridge method | rows like any method | bridges are already scanned as members |
| Load-derived rows (`overrides`, resolved `calls`) | no parameter fields | they are not parameter relationships |
| Snapshot written before the change | parameter fields are `null`; the position filter matches nothing | see §7 |

## 7. Compatibility

**Snapshots.** Old snapshots remain readable. The new columns are optional and the
reader checks for them. However, an old snapshot has no positions, and it still has
the collapsed one-row-per-type `parameter_type` rows. A position-filtered query on
such a snapshot returns no rows for that artifact, which is silently wrong.

There are two ways to handle this:

1. **Rescan.** Reactor modules are rescanned on every build, so they pick up the
   change immediately. Cached library and JDK snapshots are the risk. Without a
   format version on snapshots, the only lever is to tell users to clear the
   cache.
2. **Detect and report.** When a `parameterPosition` query reads a snapshot whose
   `detail_refs` lacks `parameter_index`, the response carries a warning listing
   the affected artifacts.

The recommendation is to do option 2, and to add a snapshot format version
separately. A version is overdue anyway, and it would turn the next format change
into an automatic rescan.

**Status:** neither option is implemented yet. Until one is, a `parameterPosition`
query on an old cached snapshot returns no rows for that artifact, without a
warning. Rescanning the artifact fixes it.

**Row counts.** `summary.total` and `by_relationship["parameter_type"]` now count
parameters instead of distinct parameter types. For most methods the two numbers
are equal. Any client that compares counts across Hierograph versions sees an
increase for methods with repeated parameter types.

**Responses.** The new `parameter` field is additive. Clients that do not know it
ignore it.

**Type level.** Type-level weights come from `refs.parquet`, which this change
does not touch.

## 8. Implementation plan

Part A is implemented as follows:

| Module | File | Change |
|--------|------|--------|
| graphstore | `model/DetailRef.kt` | add `parameterIndex`, `parameterName` and the shared `DetailRef.ORDER` |
| graphstore | `parquet/ParquetSchema.kt` | add `DetailRefs.PARAMETER_INDEX`, `PARAMETER_NAME`; extend `detailRefsSchema()` |
| graphstore | `parquet/ParquetWriter.kt` | write both columns; sort by `DetailRef.ORDER` |
| graphstore | `parquet/ParquetReader.kt` | read both columns behind `hasColumn` |
| graphstore | `detail/DetailQueryEngine.kt` | add `parameterIndex` to `DetailQuery`; filter in memory; sort by `DetailRef.ORDER`; carry the fields in `reverseEdges` |
| scanner | `ClassScanner.kt` | emit one row per parameter (and per parameter annotation) from `ParameterFacts` |
| server | `detail/IDetailDependencies.kt` | new trailing `parameterPosition: Int? = null` argument |
| server | `detail/ParquetDetailDependencies.kt` | validate and hash `parameterPosition`; skip the load-derived edges when it is set (they have no position); emit `edges[].parameter`; sort by `DetailRef.ORDER` instead of `ROW_ORDER` |
| server | `dependencyanalysis/IncomingDependenciesTool.kt`, `OutgoingDependenciesTool.kt` | the new trailing `@ToolParam`, type-level rejection, descriptions |
| server | `navigation/GraphOverviewTool.kt` | relationship descriptions |
| specs | `hierograph-graphstore-format.md` §4.3, §4.5, §5.3 and §7, `hierograph-graphstore-scanner.md`, both tool specs | document the columns, rows per parameter, descriptor positions, the filter and the field |

Part B (proposed):

| Module | File | Change |
|--------|------|--------|
| graphstore | `detail/DetailQueryEngine.kt` | `DetailQuery.fromTypes` nullable |
| server | `detail/IDetailDependencies.kt` | nullable scope ids |
| server | `detail/ParquetDetailDependencies.kt` | unconstrained open side (§4.3) |
| server | both dependency tools | drop the detail-level `toId` error; update descriptions |
| specs | both tool specs | the open form at detail level; the cost note |

Build order (see the project's Maven notes): `mvn -o install -DskipTests` on the
graphstore modules before running the server tests, so that the server does not
compile against a stale `DetailRef`.

## 9. Tests

The Part A tests are implemented and pass.

**Scanner** (`BytecodeScannerTest`, new fixture class `fix.Ledger`):

- `transfer(Circle from, Circle to, @Deprecated String memo)` yields three
  `parameter_type` rows with positions 0, 1 and 2 and their names, and one
  `parameter_annotated_by` row at position 2.
- No row of any other relationship carries a parameter.
- For every method in the fixture, the `parameter_type` rows match its
  `ParameterFacts` in position, name and type.
- The inner-class constructor `Ledger.Entry(String)` has the outer `Ledger` at
  position 0. The enum constructor of `Circle.Color` has `String` and `int` at
  positions 0 and 1.

**Format** (`ParquetWriterTest`, with a new `ParquetTestWriter.writeLegacyDetailRefs`):

- The parameter fields survive a write and read round trip. Rows written out of
  order come back in parameter order.
- A `detail_refs` file without the new columns reads with `null` parameter fields.

**Engine** (`DetailQueryEngineTest`, new fixture type `com.foo.Api`):

- `parameterIndex` keeps the rows at that position. Without a relationship it
  selects both parameter relationships.
- Rows for two parameters of the same type come out in parameter order.

**Tools** (new `DetailParameterPositionTest`; `DetailFixture` gains
`Invoice#transfer(Money from, Money to)`):

- Parameter edges carry `parameter: {position, name}`, one per parameter. Edges of
  other relationships have no `parameter` field.
- `parameterPosition` filters both tools. Without a relationship it selects only
  parameter edges.
- `INVALID_PARAMETER` is returned at the type level, for a negative position, and
  for a non-parameter relationship.
- A cursor issued for one position is rejected for another.

**Integration** (`DetailEdgeVocabularyIT`, Spring 7.0.8 snapshot): every parameter
row has a position and a name, and no other row has one. At least one method has two
rows to the same type with different positions, and its rows match its parameters.

**Part B (not yet written):** the detail-level open form returns the same rows as
the union of the per-candidate queries. `DependencyToolsOptionalToIdTest` changes
from expecting an error to expecting results.

## 10. Decisions

Resolved by the Part A implementation:

1. **Filter name: `parameterPosition`.** The issue proposed `parameterIndex`,
   matching the storage column. The tool API uses `parameterPosition` instead, to
   match the `position` field that `method_details` already returns, so that one
   concept has one name in the API. The storage column is `parameter_index`, matching
   `parameters.parquet`.
2. **Descriptor positions.** Positions follow the bytecode descriptor, so synthetic
   parameters (the outer instance, the enum `name` and `ordinal`) occupy positions.
   The format spec previously said that `index` "counts declared parameters only".
   The spec now describes descriptor positions, and a scanner test pins them.
   Skipping synthetic parameters would only work for classes compiled with
   `-parameters`. This can be revisited if callers report confusion.

Still open:

3. **Ship Part B in the same change?** The parts are independent. Part A is
   self-contained and low-risk. Part B changes the shape of a public
   tool contract (nullable scopes, the `null` scope field) and has the cost
   caveat of §4.3. The recommendation is to ship them as two pull requests, Part A
   first.
4. **The old-snapshot warning (§7).** Not yet implemented.
5. **A `parameterName` filter?** The name is stored, so a name filter is cheap to
   add. It is left out until a use case appears, because names depend on compiler
   flags and are less reliable than positions.
