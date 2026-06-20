# Detail-level dependency queries

This is the list of detail queries currently emitted by `DetailDependenciesComponent`
(`hierograph-mcp/io.hierograph.mcp.server/src/main/kotlin/io/hierograph/mcp/server/tools/detail/DetailDependenciesComponent.kt`).
They come from the `BRANCHES` table and are assembled via `UNION ALL` in
`buildCypher` / `renderBranch`.

Each row shows one Hierograph relationship kind, its source/target Neo4j labels,
the Cypher middle pattern, and an example of the rendered query (container scope
on both sides — the default expansion). Member-scope variants replace the
`(st)-[:EXTENDS|IMPLEMENTS*0..]->(so)-[:DECLARES]->(src)` chain with a direct
`id(src) = $fromMemberId` anchor (and analogously on the target side for
`TO_ENTITY` / `REVERSE_FROM_ENTITY` shapes).

## Detail-level Cypher branches

| # | `relName` | Src | Middle | Tgt | Shape |
|---|-----------|-----|--------|-----|-------|
| 1 | `throws` | `Method` | `-[r:THROWS]->` | `Type` | TO_TYPE |
| 2 | `returns` | `Method` | `-[r:RETURNS]->` | `Type` | TO_TYPE |
| 3 | `has_type` | `Field` | `-[r:OF_TYPE]->` | `Type` | TO_TYPE |
| 4 | `calls` | `Method` | `-[r:INVOKES\|VIRTUAL_INVOKES]->` | `Method` | TO_ENTITY |
| 5 | `overrides` | `Method` | `-[r:OVERRIDES]->` | `Method` | TO_ENTITY |
| 6 | `reads_field` | `Method` | `-[r:READS]->` | `Field` | TO_ENTITY |
| 7 | `writes_field` | `Method` | `-[r:WRITES]->` | `Field` | TO_ENTITY |
| 8 | `read_by` | `Field` | `<-[r:READS]-` | `Method` | REVERSE_FROM_ENTITY |
| 9 | `written_by` | `Field` | `<-[r:WRITES]-` | `Method` | REVERSE_FROM_ENTITY |
| 10 | `annotated_by` | `Method` | `-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->` | `Type` | TO_TYPE |
| 11 | `annotated_by` | `Field` | `-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->` | `Type` | TO_TYPE |
| 12 | `parameter_type` | `Method` | `-[:HAS]->(p:Parameter)-[:OF_TYPE]->` | `Type` | TO_TYPE |
| 13 | `parameter_annotated_by` | `Method` | `-[:HAS]->(p:Parameter)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->` | `Type` | TO_TYPE |
| 14 | `annotated_by` | `Type` | `-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->` | `Type` | TO_TYPE |

## Example queries (container scope on both sides)

### 1. `throws`
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Method)-[r:THROWS]->(tgt:Type)
WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
RETURN DISTINCT
  id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
  id(so)  AS srcTypeId, so.name AS srcTypeName, so.fqn AS srcTypeFqn, labels(so) AS srcTypeLabels,
  id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
  id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
  'throws' AS relName, src.firstLineNumber AS lineNumber
```

### 2. `returns`
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Method)-[r:RETURNS]->(tgt:Type)
WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
RETURN DISTINCT ..., 'returns' AS relName, src.firstLineNumber AS lineNumber
```

### 3. `has_type`
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Field)-[r:OF_TYPE]->(tgt:Type)
WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
RETURN DISTINCT ..., 'has_type' AS relName, null AS lineNumber
```

### 4. `calls`
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Method)
      -[r:INVOKES|VIRTUAL_INVOKES]->
      (tgt:Method)<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)
WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
RETURN DISTINCT ..., 'calls' AS relName, r.lineNumber AS lineNumber
```

### 5. `overrides`
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Method)
      -[r:OVERRIDES]->
      (tgt:Method)<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)
WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
RETURN DISTINCT ..., 'overrides' AS relName, src.firstLineNumber AS lineNumber
```

### 6. `reads_field`
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Method)
      -[r:READS]->
      (tgt:Field)<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)
WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
RETURN DISTINCT ..., 'reads_field' AS relName, r.lineNumber AS lineNumber
```

### 7. `writes_field`
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Method)
      -[r:WRITES]->
      (tgt:Field)<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)
WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
RETURN DISTINCT ..., 'writes_field' AS relName, r.lineNumber AS lineNumber
```

### 8. `read_by` (reverse)
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Field)
      <-[r:READS]-
      (tgt:Method)<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)
WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
RETURN DISTINCT ..., 'read_by' AS relName, r.lineNumber AS lineNumber
```

### 9. `written_by` (reverse)
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Field)
      <-[r:WRITES]-
      (tgt:Method)<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)
WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
RETURN DISTINCT ..., 'written_by' AS relName, r.lineNumber AS lineNumber
```

### 10. `annotated_by` (Method)
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Method)
      -[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
RETURN DISTINCT ..., 'annotated_by' AS relName, src.firstLineNumber AS lineNumber
```

### 11. `annotated_by` (Field)
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Field)
      -[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
RETURN DISTINCT ..., 'annotated_by' AS relName, null AS lineNumber
```

### 12. `parameter_type`
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Method)
      -[:HAS]->(p:Parameter)-[:OF_TYPE]->(tgt:Type)
WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
RETURN DISTINCT ..., 'parameter_type' AS relName, src.firstLineNumber AS lineNumber
```

### 13. `parameter_annotated_by`
```cypher
MATCH (st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:Method)
      -[:HAS]->(p:Parameter)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
RETURN DISTINCT ..., 'parameter_annotated_by' AS relName, src.firstLineNumber AS lineNumber
```

### 14. `annotated_by` (Type)
The annotated element *is* the type, so there is no `DECLARES` join: the source
collapses to `(src:Type)` and the declaring-type projection (`srcTypeId` / …)
reports `src` itself. Covers class/type-level annotations.
```cypher
MATCH (src:Type)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
WHERE id(src) IN $fromTypes AND id(tgt) IN $toTypes
RETURN DISTINCT ..., 'annotated_by' AS relName, null AS lineNumber
```

## Variants

At runtime, `buildCypher` joins the applicable branches with `UNION ALL`. The set
is narrowed by:

- **`relationship` filter** — when non-null, only the matching branch row is
  rendered.
- **`fromScope` is a member** (Method/Field) — only branches whose `srcLabel`
  matches the member's Neo4j label are kept; the source side becomes
  `MATCH (so:Type)-[:DECLARES]->(src:<label>) WHERE id(src) = $fromMemberId`.
  The `Type`-sourced `annotated_by` branch (`srcLabel = Type`) is dropped here,
  since a member scope can never be a `Type` source.
- **`toScope` is a member** — `TO_TYPE` branches are skipped; remaining branches
  whose `tgtLabel` matches the member's label render the target as
  `MATCH (tgt:<label>)<-[:DECLARES]-(to:Type) WHERE id(tgt) = $toMemberId`.
- **Empty subtree on either side** — `EMPTY_CYPHER` (a `LIMIT 0` schema-matching
  no-op) is returned instead.

## Type as edge endpoint

### As source

One detail branch uses `Type` as the source label: `annotated_by` (Type),
which models class/type-level annotations. For it the source collapses to
`(src:Type)` with no `DECLARES` join (the annotated element *is* the type), and
the declaring-type projection reports `src` itself. Every other branch's
`srcLabel` is `Method` or `Field`, where the `Type` only appears as the
*declarer* of the source member (the `(so:Type)-[:DECLARES]->(src:…)` join).
Type-to-type evidence beyond annotations is exposed instead through the
*type*-level path
(`OutgoingDependenciesTool.typeLevelDependencies`, selected via
`detail_level="type"`), where edges carry attribute flags such as
`is_extends` / `is_implements` / `is_annotated_by` rather than being filtered
by a named relationship kind.

### As target

8 of the 14 branches have `tgtLabel = Type` (the `TO_TYPE` shape): `throws`,
`returns`, `has_type`, `annotated_by` (Method), `annotated_by` (Field),
`annotated_by` (Type), `parameter_type`, `parameter_annotated_by`. The
remaining 6 (`calls`, `overrides`, `reads_field`, `writes_field`, `read_by`,
`written_by`) target `Method` or `Field`.

## Potential Type-originated relationships (not currently modeled at detail level)

Based on jQAssistant's Java schema, these are the candidate
`relName`s that *could* be added with `srcLabel = "Type"`. `extends` and
`implements` are computed at the type level (encoded in
`HGCoreDependency.attributesBitmap` as `JavaEdgeAttributes.IS_EXTENDS` /
`IS_IMPLEMENTS`) and surfaced by `outgoing_dependencies` at
`detail_level="type"` as attribute flags on a single aggregated `Type→Type`
edge; they have not been promoted to named detail-level branches.
(`annotated_by` for a `Type` source *is* now a named detail branch — see
branch 14 above — in addition to its type-level `IS_ANNOTATED_BY` attribute.)

### Type → Type

| Proposed `relName` | jQAssistant pattern | Notes |
|---|---|---|
| `extends` | `(src:Type:Class)-[r:EXTENDS]->(tgt:Type:Class)` | Encoded today as `IS_EXTENDS` attribute bit at type level. |
| `implements` | `(src:Type)-[r:IMPLEMENTS]->(tgt:Type:Interface)` | Encoded today as `IS_IMPLEMENTS` attribute bit at type level. |
| `permits` | `(src:Type)-[r:PERMITS]->(tgt:Type)` | Sealed types (Java 17+); only present if the project uses sealed classes/interfaces. Not captured today. |
| `type_parameter_bound` | `(src:Type)-[:DECLARES]->(:TypeVariable)-[:HAS_UPPER_BOUND]->(:Bound)-[:OF_RAW_TYPE]->(tgt:Type)` | Generic bounds on a type's type parameters (e.g. `class Foo<T extends Bar>`). Exact edge labels vary by jQAssistant version. Not captured today. |
| `inner_type_of` / `declares_type` | `(src:Type)-[:DECLARES]->(tgt:Type)` | Nested/inner type relationship. Arguably structural rather than a dependency. |

### Type → Member (structural)

| Proposed `relName` | Pattern | Notes |
|---|---|---|
| `declares_method` | `(src:Type)-[:DECLARES]->(tgt:Method)` | Containment, already exposed in the HG node tree (parent/children); not really a *dependency*. |
| `declares_field` | `(src:Type)-[:DECLARES]->(tgt:Field)` | Same caveat. |

### Implementation note

The `annotated_by` (Type) branch already relaxes the original "source is always
Method/Field" invariant in `renderBranch`: when `srcLabel == "Type"` the source
side emits `(src:Type)` instead of `(so:Type)-[:DECLARES]->(src:<member>)`, with
`so = src` for the projected `srcTypeId` / `srcTypeName` / `srcTypeFqn` fields.
Any further type-sourced relationships (e.g. `extends`, `implements`) would
follow the same shape.
