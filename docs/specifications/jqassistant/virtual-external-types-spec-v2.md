# Implementation Spec: Virtual Canonical Nodes for Unparsed Types and Packages

## Overview

jQAssistant's Java scanner creates per-artifact stub `:Type` nodes for any class referenced in bytecode but not itself parsed. When the same external type (e.g. `java.util.List`) is referenced by N scanned artifacts, N independent stub nodes exist in the graph — each with only `fqn` set, no `byteCodeVersion`, and signature-only `:Method`/`:Field` children. There are no `:Package` nodes for the packages these types belong to, because no `.class` file was scanned to create them.

The built-in `classpath:Resolve` concept solves an adjacent problem (linking references across scanned artifacts to fully-scanned counterparts) but does nothing for the all-stub case. It also leaves the duplicate stubs in place.

This spec defines a set of jQAssistant concepts that produce **canonical virtual nodes** — one `:Virtual:Type` per external FQN, one `:Virtual:Package` per package FQN, and one `:Virtual:Artifact` ("External") that owns the whole virtual subgraph — without modifying or deleting the original stubs. Stubs are linked to their canonicals via `:RESOLVES_TO`. Packages contain their types and their child packages via `:CONTAINS`. The `External` artifact contains every virtual package and every virtual type flatly, mirroring how jQAssistant's Java scanner relates a real `:Artifact:Jar` to its packages and types.

## Goals

- One canonical node per external type FQN, queryable as `:Virtual:Type`.
- One canonical node per external package FQN, queryable as `:Virtual:Package`.
- Full package hierarchy (`com` → `com.acme` → `com.acme.foo`) materialized as `:CONTAINS` edges.
- A single `:Virtual:Artifact {name: "External"}` that flatly `:CONTAINS` every virtual package and every virtual type, mirroring jQAssistant's Java scanner pattern for real `:Artifact:Jar` nodes.
- Canonical dependencies: dependencies that target external stubs are additively lifted onto the canonical `:Virtual:Type`, reachable in a single `:DEPENDS_ON` hop.
- Canonical structural edges: `:EXTENDS`, `:IMPLEMENTS`, and `:ANNOTATED_BY` relationships that target external stubs are additively lifted onto the canonical `:Virtual:Type` as well, so supertype, interface, and annotation analysis reaches the canonical node in a single hop.
- Non-destructive: original stubs and their relationships are untouched.
- Idempotent: re-running the concepts does not duplicate nodes or edges.

## Non-goals

- Rewriting or deleting the `:INVOKES`, `:READS`, `:WRITES`, `:DEPENDS_ON`, `:EXTENDS`, `:IMPLEMENTS`, or `:ANNOTATED_BY` edges that already exist on the original stubs — these stay exactly as the scanner produced them. (Concepts 5–8 *add* parallel `:DEPENDS_ON`, `:EXTENDS`, `:IMPLEMENTS`, and `:ANNOTATED_BY` edges from the depending/declaring node to the canonical `:Virtual:Type`; they never modify the stub's own edges. Traversals that prefer the explicit form can still follow the original edge then `:RESOLVES_TO`.)
- Inferring members (methods, fields) of external types beyond what the bytecode references already produced as signature stubs.
- Merging virtual nodes with real `:Package` nodes created by the Java scanner. The `:Virtual` label keeps them distinct.

## Design decisions

### Per-artifact stubs are kept

Each stub retains its place in the artifact that referenced it. This preserves per-artifact dependency provenance ("artifact A references `java.util.List`, artifact B does not"). The canonical node is *additional*, not a replacement.

### Separate `:Virtual` label

Canonical nodes carry `:Virtual` in addition to `:Type` or `:Package`. This prevents accidental matches by queries written against the real Java model and makes the virtual layer opt-in. Queries that want the unified view explicitly match on the virtual label.

### `:RESOLVES_TO` for stub→canonical links

Reuses the relationship type already established by `classpath:Resolve` for "this reference points at a richer representation." A query that follows `(:Type)-[:RESOLVES_TO*]->(target)` works uniformly whether `target` is a fully-scanned type from `classpath:Resolve` or a virtual node from this spec.

### Lifted `:DEPENDS_ON` to canonical types (additive)

Concept 5 mirrors each `(a)-[:DEPENDS_ON]->(stub)` onto `(a)-[:DEPENDS_ON]->(:Virtual:Type)` when the stub resolves to a canonical node. This is purely additive — the stub edge is untouched — and lets dependency analysis (DSM, layering, cycle detection) treat the canonical virtual type as a first-class dependency target without threading a `:RESOLVES_TO` hop into every query. The lifted edge carries a `weight` summed from the stub edges it consolidates, matching the weighted `:DEPENDS_ON` the Java scanner emits.

### Lifted structural edges: `:EXTENDS`, `:IMPLEMENTS`, `:ANNOTATED_BY` (additive)

Concepts 6–8 mirror the structural relationships that target external stubs onto the canonical `:Virtual:Type`, the same way concept 5 mirrors `:DEPENDS_ON`:

- **`:EXTENDS`** (concept 6): a real class/interface whose superclass or super-interface is unparsed (e.g. a type that `extends` an `org.neo4j.driver.*` stub) gets an additional `(a)-[:EXTENDS]->(:Virtual:Type)` edge to the canonical supertype.
- **`:IMPLEMENTS`** (concept 7): `(a:Type)-[:IMPLEMENTS]->(stub)` gets a parallel `(a)-[:IMPLEMENTS]->(:Virtual:Type)`.
- **`:ANNOTATED_BY`** (concept 8): an external annotation type — reached via the scanner's `(x)-[:ANNOTATED_BY]->(:Annotation)-[:OF_TYPE]->(stub)` shape — gets the `:OF_TYPE` leg lifted, i.e. a parallel `(:Annotation)-[:OF_TYPE]->(:Virtual:Type)` edge so the value node reaches the canonical annotation type while preserving the native two-hop shape.

Each lifted edge is tagged **`resolved = true`** and (for `:EXTENDS`/`:IMPLEMENTS`/`:OF_TYPE`) copies the original edge's properties — following jQAssistant's own `java-classpath:Resolve` idiom, so consumers can distinguish a synthesized canonical edge from the raw stub edge. The lifted `:DEPENDS_ON` additionally sums stub weights (so it keeps its aggregation rather than copying a single edge's properties). All lifts are purely additive — the original edges are untouched — and non-cascading, since virtual types have no outgoing `:EXTENDS`/`:IMPLEMENTS`/`:DEPENDS_ON`/`:RESOLVES_TO` for the pattern to re-match. Re-running is idempotent via `MERGE`.

**Note on the `:ANNOTATED_BY` shape.** The scanner points `:ANNOTATED_BY` at an intermediate `:Value:Annotation` node, which in turn carries `:OF_TYPE` to the annotation's type. Concept 8 deliberately **preserves** that two-hop shape, lifting only the `:OF_TYPE` leg onto the canonical `:Virtual:Type` — exactly the shape the scanner produces for *internal* annotations (`:ANNOTATED_BY → :Value:Annotation → :OF_TYPE → :Type`). So every consumer that walks `(x)-[:ANNOTATED_BY]->(:Annotation)-[:OF_TYPE]->(type)` — `type_details`, the type-level `is_annotated_by` classifier, the detail-level `annotated_by` branch — handles external and internal annotations uniformly. (An earlier version instead *collapsed* external annotations to a one-hop `(x)-[:ANNOTATED_BY]->(:Virtual:Type)` edge, a shape the scanner never emits for internal annotations, which is why internal class-level annotations were invisible to those two-hop consumers.)

### Flat containment from the `External` artifact

The Java scanner emits `(:Artifact:Jar)-[:CONTAINS]->(:Package)` and `(:Artifact:Jar)-[:CONTAINS]->(:Type)` for **every** package and type in a JAR, not just the top-level ones. Top-level packages are then identified by filtering — `WHERE NOT (:Package)-[:CONTAINS]->(b)` — against the parallel `:Package`/`:Type` hierarchy. The `External` virtual artifact follows the same pattern so that queries already written against real artifacts (including slizaa's top-level node discovery) work on the virtual subgraph by swapping `:Artifact:Jar` for `:Virtual:Artifact`.

### Queries against the real Java model must exclude `:Virtual`

Virtual nodes carry **both** the `:Virtual` label and one of `:Type` / `:Package` / `:Artifact`. That means a query written as `MATCH (t:Type)` matches real types *and* `:Virtual:Type` canonicals; `MATCH (p:Package)` likewise includes virtual packages, and `MATCH (a:Artifact)` includes the `External` artifact. Most queries authored against the Java scanner's output assume only real nodes — running them unchanged against a graph that has the virtual layer will silently include the wrong nodes.

Rule: **every `MATCH`/`MERGE` whose pattern is meant to address real `:Type`, `:Package`, or `:Artifact` nodes must explicitly exclude `:Virtual`**. The standard form is:

```cypher
MATCH (t:Type)   WHERE NOT t:Virtual ...
MATCH (p:Package) WHERE NOT p:Virtual ...
MATCH (a:Artifact) WHERE NOT a:Virtual ...
```

This applies to:

- The concepts in this spec (in particular `hierograph:VirtualExternalType`, whose `MATCH (t:Type)` would otherwise re-pick up its own outputs on a re-run and create `(:Virtual:Type)-[:RESOLVES_TO]->(:Virtual:Type)` self-loops).
- Any downstream rule or ad-hoc query that operates on the Java model (dependency reports, metrics, sanity checks). If such a query is missing the `NOT x:Virtual` guard, treat it as a bug.

Queries that intentionally target the virtual layer must match on `:Virtual` explicitly (e.g., `:Virtual:Type`, `:Virtual:Package`, `:Virtual:Artifact`) so the symmetry is enforced by both sides.

### Exclusions

The following are deliberately excluded from virtual-type creation:

- **Primitives**: `byte`, `short`, `int`, `long`, `char`, `float`, `double`, `boolean`, `void`.
- **JVM array descriptors**: any FQN starting with `[` (e.g. `[D`, `[Ljava/lang/String;`).
- **Default-package types**: any FQN with no `.` (these are rare and usually represent edge cases not worth a canonical node).
- **JDK/runtime ubiquitous types**: any FQN starting with `java.lang.` or `kotlin.`. These are referenced by nearly every type; canonicalizing them adds noise without analytical value.
- **Nullability annotations**: `org.jetbrains.annotations.NotNull` and `org.jetbrains.annotations.Nullable`. These Kotlin-emitted nullability annotations appear on nearly every member and parameter and would otherwise dominate the lifted `:ANNOTATED_BY` edges (concept 8) with no analytical value. Excluding them at concept 1 propagates automatically: no `:Virtual:Type`, no `:RESOLVES_TO`, and therefore no lifted `:ANNOTATED_BY` edge is created for them.

Array element types can be derived in a separate concept if needed; this spec doesn't cover that.

## Prerequisites

No explicit setup is required. The Java plugin's descriptor framework already creates indexes on `:Type(fqn)` and `:Package(fqn)` when the store is initialized, which is what the MERGEs below benefit from. Multi-label uniqueness constraints (e.g., `FOR (v:Virtual:Type)`) are not expressible in Cypher — only single-label patterns are allowed in `CREATE CONSTRAINT ... FOR (...)` — so we don't add explicit constraints for the virtual layer. Idempotency is guaranteed by `MERGE` semantics, not by a unique constraint.

## Concepts

### 1. `hierograph:VirtualExternalType`

Creates a `:Virtual:Type` node for every unparsed type stub, keyed by FQN, and links each stub to it.

**Inputs**: existing `:Type` nodes with no `byteCodeVersion`, excluding primitives, arrays, default-package names, `java.lang.*` / `kotlin.*` types, and `org.jetbrains.annotations.NotNull` / `Nullable`.

**Outputs**:
- One `:Virtual:Type {fqn, name}` node per distinct external FQN.
- One `(:Type)-[:RESOLVES_TO]->(:Virtual:Type)` edge per stub.

**Cypher**:

```cypher
MATCH (t:Type)
WHERE NOT t:Virtual
  AND t.byteCodeVersion IS NULL
  AND NOT t.fqn IN ["byte","short","int","long","char","float","double","boolean","void"]
  AND NOT t.fqn IN ["org.jetbrains.annotations.NotNull","org.jetbrains.annotations.Nullable"]
  AND NOT t.fqn STARTS WITH "["
  AND NOT t.fqn STARTS WITH "java.lang."
  AND NOT t.fqn STARTS WITH "kotlin."
  AND t.fqn CONTAINS "."
WITH t, t.fqn AS fqn
MERGE (v:Virtual:Type {fqn: fqn})
  ON CREATE SET v.name = split(fqn, ".")[-1]
MERGE (t)-[:RESOLVES_TO]->(v)
RETURN count(DISTINCT v) AS VirtualTypes
```

### 2. `hierograph:VirtualExternalPackage`

Creates a `:Virtual:Package` node for each virtual type's package and links package to type.

**Depends on**: `hierograph:VirtualExternalType`.

**Inputs**: `:Virtual:Type` nodes created by concept 1.

**Outputs**:
- One `:Virtual:Package {fqn, name}` node per distinct package FQN.
- One `(:Virtual:Package)-[:CONTAINS]->(:Virtual:Type)` edge per type.

**Cypher**:

```cypher
MATCH (v:Virtual:Type)
WITH v, substring(v.fqn, 0, size(v.fqn) - size(v.name) - 1) AS pkgFqn
MERGE (p:Virtual:Package {fqn: pkgFqn})
  ON CREATE SET p.name = split(pkgFqn, ".")[-1]
MERGE (p)-[:CONTAINS]->(v)
RETURN count(DISTINCT p) AS VirtualPackages
```

### 3. `hierograph:VirtualPackageHierarchy`

Builds the parent-child hierarchy between virtual packages by walking each FQN's dotted path and materializing every ancestor.

**Depends on**: `hierograph:VirtualExternalPackage`.

**Inputs**: `:Virtual:Package` nodes whose FQN contains a dot (top-level packages like `java` have nothing to do).

**Outputs**:
- Additional `:Virtual:Package` nodes for any intermediate packages not already created.
- `(:Virtual:Package)-[:CONTAINS]->(:Virtual:Package)` edges between each parent and its immediate child.

**Cypher**:

```cypher
MATCH (p:Virtual:Package)
WHERE p.fqn CONTAINS "."
WITH p, split(p.fqn, ".") AS parts
UNWIND range(1, size(parts) - 1) AS i
WITH parts, i,
     reduce(s = head(parts), x IN parts[1..i] | s + "." + x) AS parentFqn,
     reduce(s = head(parts), x IN parts[1..i+1] | s + "." + x) AS childFqn
MERGE (parent:Virtual:Package {fqn: parentFqn})
  ON CREATE SET parent.name = split(parentFqn, ".")[-1]
MERGE (child:Virtual:Package {fqn: childFqn})
  ON CREATE SET child.name = split(childFqn, ".")[-1]
MERGE (parent)-[:CONTAINS]->(child)
RETURN count(DISTINCT parent) AS ParentPackages
```

Note: this concept also ensures that single-segment ancestors (`java`, `com`, `org`) exist as `:Virtual:Package` nodes even if no type lives directly in them.

### 4. `hierograph:VirtualExternalArtifact`

Creates a single `:Virtual:Artifact {name: "External"}` node and links it to every `:Virtual:Package` and every `:Virtual:Type` via `:CONTAINS`. Flat containment — every package and every type, not just top-level ones — mirrors how jQAssistant's Java scanner relates a real `:Artifact:Jar` to its contents.

**Depends on**: `hierograph:VirtualPackageHierarchy` (so intermediate ancestor packages such as `java`, `com`, `org` are present before they get linked).

**Inputs**: `:Virtual:Type` nodes from concept 1; `:Virtual:Package` nodes from concepts 2 and 3.

**Outputs**:
- One `:Virtual:Artifact {name: "External"}` node.
- One `(:Virtual:Artifact)-[:CONTAINS]->(:Virtual:Package)` edge per virtual package.
- One `(:Virtual:Artifact)-[:CONTAINS]->(:Virtual:Type)` edge per virtual type.

**Cypher**:

```cypher
MERGE (a:Virtual:Artifact {name: "External"})
WITH a
OPTIONAL MATCH (p:Virtual:Package)
FOREACH (_ IN CASE WHEN p IS NULL THEN [] ELSE [1] END |
  MERGE (a)-[:CONTAINS]->(p)
)
WITH a, count(p) AS Packages
OPTIONAL MATCH (t:Virtual:Type)
FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END |
  MERGE (a)-[:CONTAINS]->(t)
)
RETURN Packages, count(t) AS Types
```

The `OPTIONAL MATCH` + `FOREACH` guard keeps the concept from failing on an empty graph (which would otherwise drop the row before the second `MATCH` and return zero rows, violating jQAssistant's "at least one row" rule).

Top-level packages within the virtual subgraph remain discoverable with the same filter pattern the Java scanner uses:

```cypher
MATCH (a:Virtual:Artifact)-[:CONTAINS]->(p:Virtual:Package)
WHERE NOT (:Virtual:Package)-[:CONTAINS]->(p)
RETURN p
```

### 5. `hierograph:VirtualExternalTypeDependency`

Lifts each dependency that targets an external stub onto the stub's canonical `:Virtual:Type`, so the canonical view is reachable in a single `:DEPENDS_ON` hop instead of via `:DEPENDS_ON` + `:RESOLVES_TO`.

**Depends on**: `hierograph:VirtualExternalType` (which creates the `:RESOLVES_TO` edges).

**Inputs**: any real node `a` with `(a)-[:DEPENDS_ON]->(b)` where the stub `b` resolves to a `:Virtual:Type` `c`.

**Outputs**:
- One `(a)-[:DEPENDS_ON]->(c:Virtual:Type)` edge per distinct `(a, c)` pair, with `weight` summed from the underlying stub edges and tagged `resolved = true`.

**Cypher**:

```cypher
MATCH (a)-[r1:DEPENDS_ON]->(b)-[r2:RESOLVES_TO]->(c:Virtual:Type)
WHERE NOT a:Virtual
WITH a, c, sum(coalesce(r1.weight, 1)) AS weight
MERGE (a)-[d:DEPENDS_ON]->(c)
  SET d.weight = weight, d.resolved = true
RETURN count(*) AS LiftedDependencies
```

The lifted edge is tagged `resolved = true`, mirroring jQAssistant's own `java-classpath:Resolve` concepts, so consumers can distinguish the synthesized canonical edge from the raw stub edge. Unlike concepts 6–8 the weight is summed across all underlying stub edges rather than copied from one, so this rule keeps its aggregation instead of `SET d = properties(...)`. The original `(a)-[:DEPENDS_ON]->(b)` edge to the stub is left in place; this concept only *adds* a parallel edge to the canonical node. `WHERE NOT a:Virtual` follows the spec's rule that any pattern addressing real source nodes excludes `:Virtual`. Because the leg requires `c:Virtual:Type`, the concept also ignores any `:RESOLVES_TO` edges `classpath:Resolve` may have created toward real, fully-scanned types — it lifts dependencies onto virtual canonicals only. Virtual types have no outgoing `:DEPENDS_ON` or `:RESOLVES_TO`, so the lifted edge can never re-match either leg of the pattern: the concept is non-cascading. Re-running it is idempotent — the `(a, c)` grouping makes `weight` a pure function of the current graph, so `SET d.weight = weight` overwrites with the same value rather than accumulating.

### 6. `hierograph:VirtualExternalExtends`

Lifts each `:EXTENDS` relationship that targets an external stub onto the stub's canonical `:Virtual:Type`, so a type's external superclass or super-interface is reachable in a single `:EXTENDS` hop.

**Depends on**: `hierograph:VirtualExternalType` (which creates the `:RESOLVES_TO` edges).

**Inputs**: any real `:Type` `a` with `(a)-[:EXTENDS]->(b)` where the stub `b` resolves to a `:Virtual:Type` `c`.

**Outputs**:
- One `(a)-[:EXTENDS]->(c:Virtual:Type)` edge per distinct `(a, c)` pair, copying the original edge's properties and tagged `resolved = true`.

**Cypher**:

```cypher
MATCH (a:Type)-[extends:EXTENDS]->(b)-[:RESOLVES_TO]->(c:Virtual:Type)
WHERE NOT a:Virtual
CALL {
  WITH a, extends, c
  MERGE (a)-[extends1:EXTENDS]->(c)
  SET extends1 = properties(extends), extends1.resolved = true
} IN TRANSACTIONS
RETURN count(*) AS LiftedExtends
```

This follows jQAssistant's own `java-classpath:Resolve` idiom verbatim: an additive `MERGE` per row inside `CALL { … } IN TRANSACTIONS`, copying the original edge's properties and tagging the lifted edge `resolved = true` so consumers can tell it from the raw stub edge (which is left in place). Virtual types have no outgoing `:EXTENDS`, so the lifted edge can never re-match the pattern — non-cascading and idempotent.

### 7. `hierograph:VirtualExternalImplements`

Lifts each `:IMPLEMENTS` relationship that targets an external stub onto the stub's canonical `:Virtual:Type`.

**Depends on**: `hierograph:VirtualExternalType`.

**Inputs**: any real `:Type` `a` with `(a)-[:IMPLEMENTS]->(b)` where the stub `b` resolves to a `:Virtual:Type` `c`.

**Outputs**:
- One `(a)-[:IMPLEMENTS]->(c:Virtual:Type)` edge per distinct `(a, c)` pair, copying the original edge's properties and tagged `resolved = true`.

**Cypher**:

```cypher
MATCH (a:Type)-[implements:IMPLEMENTS]->(b)-[:RESOLVES_TO]->(c:Virtual:Type)
WHERE NOT a:Virtual
CALL {
  WITH a, implements, c
  MERGE (a)-[implements1:IMPLEMENTS]->(c)
  SET implements1 = properties(implements), implements1.resolved = true
} IN TRANSACTIONS
RETURN count(*) AS LiftedImplements
```

Identical in shape and guarantees to concept 6, swapping `:EXTENDS` for `:IMPLEMENTS`.

### 8. `hierograph:VirtualExternalAnnotatedBy`

Lifts the `:OF_TYPE` leg of each external annotation onto the annotation-type stub's canonical `:Virtual:Type`, so the annotation value node points straight at the canonical annotation type. This **preserves** the scanner's native two-hop annotation shape (`:ANNOTATED_BY → :Value:Annotation → :OF_TYPE → :Type`) rather than collapsing it — mirroring how concepts 5–7 lift `DEPENDS_ON` / `EXTENDS` / `IMPLEMENTS` onto the canonical virtual type without changing the relationship shape. Unlike `EXTENDS` / `IMPLEMENTS`, which jQAssistant models as direct `Type→Type` edges, `ANNOTATED_BY` always routes through the annotation value node; an earlier version of this concept collapsed external annotations to a one-hop `(x)-[:ANNOTATED_BY]->(c)` edge — a shape the scanner never produces for *internal* annotations — which left external annotations modelled differently from internal ones and unreachable by the canonical `:ANNOTATED_BY → :Annotation → :OF_TYPE` traversal (used e.g. by `type_details` and the detail-level `annotated_by` branch).

**Depends on**: `hierograph:VirtualExternalType`.

**Inputs**: any real node `x` with `(x)-[:ANNOTATED_BY]->(v:Annotation)-[:OF_TYPE]->(b)` where the annotation-type stub `b` resolves to a `:Virtual:Type` `c`. `x` may be a `:Type`, `:Method`, `:Field`, or `:Parameter` — every annotated element the scanner produced.

**Outputs**:
- One `(v:Annotation)-[:OF_TYPE]->(c:Virtual:Type)` edge per distinct `(v, c)` pair, copying the original edge's properties and tagged `resolved = true`.

**Cypher**:

```cypher
MATCH (x)-[:ANNOTATED_BY]->(v:Annotation)-[ofType:OF_TYPE]->(b)-[:RESOLVES_TO]->(c:Virtual:Type)
WHERE NOT x:Virtual
CALL {
  WITH v, ofType, c
  MERGE (v)-[ofType1:OF_TYPE]->(c)
  SET ofType1 = properties(ofType), ofType1.resolved = true
} IN TRANSACTIONS
RETURN count(*) AS LiftedAnnotationTypes
```

Following the same `java-classpath:Resolve` idiom as concepts 6–7, the lifted `:OF_TYPE` edge copies the original edge's properties and is tagged `resolved = true`. The original `(v)-[:OF_TYPE]->(b)` edge to the stub annotation type is left in place. The lifted target is a `:Virtual:Type`, which has no outgoing `:RESOLVES_TO`, so the pattern never re-matches — non-cascading and idempotent.

## Rule file

Save as `jqassistant/virtual-external.xml` in the project root:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jqassistant-rules xmlns="http://schema.jqassistant.org/rule/v2.9"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://schema.jqassistant.org/rule/v2.9
                                       https://jqassistant.github.io/jqassistant/current/schema/jqassistant-rule-v2.9.xsd">

  <group id="hierograph:virtual-external">
    <includeConcept refId="hierograph:VirtualExternalType"/>
    <includeConcept refId="hierograph:VirtualExternalPackage"/>
    <includeConcept refId="hierograph:VirtualPackageHierarchy"/>
    <includeConcept refId="hierograph:VirtualExternalArtifact"/>
    <includeConcept refId="hierograph:VirtualExternalTypeDependency"/>
    <includeConcept refId="hierograph:VirtualExternalExtends"/>
    <includeConcept refId="hierograph:VirtualExternalImplements"/>
    <includeConcept refId="hierograph:VirtualExternalAnnotatedBy"/>
  </group>

  <concept id="hierograph:VirtualExternalType">
    <description>
      For every unparsed (stub) :Type referenced in the graph, create a canonical :Virtual:Type
      node keyed by fqn, and link each stub to it via :RESOLVES_TO. Primitives, JVM array
      descriptors, default-package types, java.lang.* / kotlin.* types, and the ubiquitous
      org.jetbrains.annotations.NotNull / Nullable nullability annotations are excluded.
    </description>
    <cypher><![CDATA[
      MATCH (t:Type)
      WHERE NOT t:Virtual
        AND t.byteCodeVersion IS NULL
        AND NOT t.fqn IN ["byte","short","int","long","char","float","double","boolean","void"]
        AND NOT t.fqn IN ["org.jetbrains.annotations.NotNull","org.jetbrains.annotations.Nullable"]
        AND NOT t.fqn STARTS WITH "["
        AND NOT t.fqn STARTS WITH "java.lang."
        AND NOT t.fqn STARTS WITH "kotlin."
        AND t.fqn CONTAINS "."
      WITH t, t.fqn AS fqn
      MERGE (v:Virtual:Type {fqn: fqn})
        ON CREATE SET v.name = split(fqn, ".")[-1]
      MERGE (t)-[:RESOLVES_TO]->(v)
      RETURN count(DISTINCT v) AS VirtualTypes
    ]]></cypher>
  </concept>

  <concept id="hierograph:VirtualExternalPackage">
    <requiresConcept refId="hierograph:VirtualExternalType"/>
    <description>
      For every :Virtual:Type, create a canonical :Virtual:Package node derived from the type's
      FQN (everything before the last dot), and link package to type via :CONTAINS.
    </description>
    <cypher><![CDATA[
      MATCH (v:Virtual:Type)
      WITH v, substring(v.fqn, 0, size(v.fqn) - size(v.name) - 1) AS pkgFqn
      MERGE (p:Virtual:Package {fqn: pkgFqn})
        ON CREATE SET p.name = split(pkgFqn, ".")[-1]
      MERGE (p)-[:CONTAINS]->(v)
      RETURN count(DISTINCT p) AS VirtualPackages
    ]]></cypher>
  </concept>

  <concept id="hierograph:VirtualPackageHierarchy">
    <requiresConcept refId="hierograph:VirtualExternalPackage"/>
    <description>
      Build the parent-child hierarchy between :Virtual:Package nodes by walking each package's
      FQN and creating ancestor packages, linked by :CONTAINS.
    </description>
    <cypher><![CDATA[
      MATCH (p:Virtual:Package)
      WHERE p.fqn CONTAINS "."
      WITH p, split(p.fqn, ".") AS parts
      UNWIND range(1, size(parts) - 1) AS i
      WITH parts, i,
           reduce(s = head(parts), x IN parts[1..i] | s + "." + x) AS parentFqn,
           reduce(s = head(parts), x IN parts[1..i+1] | s + "." + x) AS childFqn
      MERGE (parent:Virtual:Package {fqn: parentFqn})
        ON CREATE SET parent.name = split(parentFqn, ".")[-1]
      MERGE (child:Virtual:Package {fqn: childFqn})
        ON CREATE SET child.name = split(childFqn, ".")[-1]
      MERGE (parent)-[:CONTAINS]->(child)
      RETURN count(DISTINCT parent) AS ParentPackages
    ]]></cypher>
  </concept>

  <concept id="hierograph:VirtualExternalArtifact">
    <requiresConcept refId="hierograph:VirtualPackageHierarchy"/>
    <description>
      Create a single :Virtual:Artifact {name: "External"} node and link it via :CONTAINS to every
      :Virtual:Package and every :Virtual:Type. Flat containment mirrors how jQAssistant's Java
      scanner relates a real :Artifact:Jar to its contents (every package and every type, not just
      top-level ones), so existing queries that filter by NOT (:Package)-[:CONTAINS]->(b) work
      against the virtual subgraph too.
    </description>
    <cypher><![CDATA[
      MERGE (a:Virtual:Artifact {name: "External"})
      WITH a
      OPTIONAL MATCH (p:Virtual:Package)
      FOREACH (_ IN CASE WHEN p IS NULL THEN [] ELSE [1] END |
        MERGE (a)-[:CONTAINS]->(p)
      )
      WITH a, count(p) AS Packages
      OPTIONAL MATCH (t:Virtual:Type)
      FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END |
        MERGE (a)-[:CONTAINS]->(t)
      )
      RETURN Packages, count(t) AS Types
    ]]></cypher>
  </concept>

  <concept id="hierograph:VirtualExternalTypeDependency">
    <requiresConcept refId="hierograph:VirtualExternalType"/>
    <description>
      For every dependency that targets an external stub, (a)-[:DEPENDS_ON]->(b), where the stub b
      resolves to a canonical :Virtual:Type c via :RESOLVES_TO, additively create a parallel
      (a)-[:DEPENDS_ON]->(c) edge so the canonical view is reachable in a single hop. The original
      stub edge is left untouched; the lifted edge's weight is the summed weight of the underlying
      edges. Real source nodes are guaranteed by NOT a:Virtual, and the c:Virtual:Type leg ignores
      :RESOLVES_TO edges that classpath:Resolve may have created toward real types.
    </description>
    <cypher><![CDATA[
      MATCH (a)-[r1:DEPENDS_ON]->(b)-[r2:RESOLVES_TO]->(c:Virtual:Type)
      WHERE NOT a:Virtual
      WITH a, c, sum(coalesce(r1.weight, 1)) AS weight
      MERGE (a)-[d:DEPENDS_ON]->(c)
        SET d.weight = weight, d.resolved = true
      RETURN count(*) AS LiftedDependencies
    ]]></cypher>
  </concept>

  <concept id="hierograph:VirtualExternalExtends">
    <requiresConcept refId="hierograph:VirtualExternalType"/>
    <description>
      For every :EXTENDS that targets an external stub, (a)-[:EXTENDS]->(b), where the stub b
      resolves to a canonical :Virtual:Type c via :RESOLVES_TO, additively create a parallel
      (a)-[:EXTENDS]->(c) edge so the canonical supertype is reachable in a single hop. The original
      stub edge is left untouched. The lifted edge is unweighted. Real source types are guaranteed by
      NOT a:Virtual, and the c:Virtual:Type leg ignores :RESOLVES_TO edges classpath:Resolve may have
      created toward real types.
    </description>
    <cypher><![CDATA[
      MATCH (a:Type)-[extends:EXTENDS]->(b)-[:RESOLVES_TO]->(c:Virtual:Type)
      WHERE NOT a:Virtual
      CALL {
        WITH a, extends, c
        MERGE (a)-[extends1:EXTENDS]->(c)
        SET extends1 = properties(extends), extends1.resolved = true
      } IN TRANSACTIONS
      RETURN count(*) AS LiftedExtends
    ]]></cypher>
  </concept>

  <concept id="hierograph:VirtualExternalImplements">
    <requiresConcept refId="hierograph:VirtualExternalType"/>
    <description>
      For every :IMPLEMENTS that targets an external stub, (a)-[:IMPLEMENTS]->(b), where the stub b
      resolves to a canonical :Virtual:Type c via :RESOLVES_TO, additively create a parallel
      (a)-[:IMPLEMENTS]->(c) edge so the canonical interface is reachable in a single hop. The original
      stub edge is left untouched. The lifted edge is unweighted.
    </description>
    <cypher><![CDATA[
      MATCH (a:Type)-[implements:IMPLEMENTS]->(b)-[:RESOLVES_TO]->(c:Virtual:Type)
      WHERE NOT a:Virtual
      CALL {
        WITH a, implements, c
        MERGE (a)-[implements1:IMPLEMENTS]->(c)
        SET implements1 = properties(implements), implements1.resolved = true
      } IN TRANSACTIONS
      RETURN count(*) AS LiftedImplements
    ]]></cypher>
  </concept>

  <concept id="hierograph:VirtualExternalAnnotatedBy">
    <requiresConcept refId="hierograph:VirtualExternalType"/>
    <description>
      For every element annotated by an external annotation type, reached via the scanner's
      (x)-[:ANNOTATED_BY]->(v:Annotation)-[:OF_TYPE]->(b) shape where the annotation-type stub b
      resolves to a canonical :Virtual:Type c, additively lift the OF_TYPE leg by creating a parallel
      (v)-[:OF_TYPE]->(c) edge from the annotation value node to the canonical annotation type. This
      preserves jQAssistant's native two-hop annotation shape (ANNOTATED_BY to a :Value:Annotation,
      then OF_TYPE to the annotation :Type), mirroring how the EXTENDS / IMPLEMENTS / DEPENDS_ON
      concepts lift onto the canonical virtual type without changing the relationship shape. The
      original (v)-[:OF_TYPE]->(b) stub edge is left untouched. x may be a :Type, :Method, :Field, or
      :Parameter. The lifted edge is unweighted; its target is always a :Virtual:Type, which has no
      outgoing :RESOLVES_TO, so it cannot re-match the pattern.
    </description>
    <cypher><![CDATA[
      MATCH (x)-[:ANNOTATED_BY]->(v:Annotation)-[ofType:OF_TYPE]->(b)-[:RESOLVES_TO]->(c:Virtual:Type)
      WHERE NOT x:Virtual
      CALL {
        WITH v, ofType, c
        MERGE (v)-[ofType1:OF_TYPE]->(c)
        SET ofType1 = properties(ofType), ofType1.resolved = true
      } IN TRANSACTIONS
      RETURN count(*) AS LiftedAnnotationTypes
    ]]></cypher>
  </concept>

</jqassistant-rules>
```

## Activation

Activate the `hierograph:virtual-external` group on the command line:

```bash
jqassistant.sh analyze --groups hierograph:virtual-external
```

Or via `.jqassistant.yml`:

```yaml
jqassistant:
  analyze:
    groups:
      - hierograph:virtual-external
```

Or via Maven property:

```bash
mvn jqassistant:analyze -Djqassistant.analyze.groups=hierograph:virtual-external
```

Because of the `requiresConcept` chain, the group only needs to include the leaf concept(s); dependencies pull in their prerequisites automatically. The group above lists all eight for discoverability.

## Verification

After running, confirm the concepts produced output:

```cypher
MATCH (v:Virtual:Type) RETURN count(v) AS VirtualTypes;
MATCH (p:Virtual:Package) RETURN count(p) AS VirtualPackages;
MATCH (a:Virtual:Artifact) RETURN count(a) AS VirtualArtifacts;
MATCH (:Type)-[r:RESOLVES_TO]->(:Virtual:Type) RETURN count(r) AS StubLinks;
MATCH (:Virtual:Package)-[r:CONTAINS]->(:Virtual:Type) RETURN count(r) AS PackageContents;
MATCH (:Virtual:Package)-[r:CONTAINS]->(:Virtual:Package) RETURN count(r) AS PackageHierarchy;
MATCH (:Virtual:Artifact)-[r:CONTAINS]->(:Virtual:Package) RETURN count(r) AS ArtifactPackages;
MATCH (:Virtual:Artifact)-[r:CONTAINS]->(:Virtual:Type) RETURN count(r) AS ArtifactTypes;
MATCH (a)-[r:DEPENDS_ON]->(:Virtual:Type) WHERE NOT a:Virtual RETURN count(r) AS LiftedDependencies;
MATCH (a)-[r:EXTENDS]->(:Virtual:Type) WHERE NOT a:Virtual RETURN count(r) AS LiftedExtends;
MATCH (a)-[r:IMPLEMENTS]->(:Virtual:Type) WHERE NOT a:Virtual RETURN count(r) AS LiftedImplements;
MATCH (:Annotation)-[r:OF_TYPE]->(:Virtual:Type) RETURN count(r) AS LiftedAnnotationTypes;
```

Sanity-check a known external type, e.g.:

```cypher
MATCH (v:Virtual:Type {fqn: "java.util.List"})
OPTIONAL MATCH (stub:Type)-[:RESOLVES_TO]->(v) WHERE NOT stub:Virtual
OPTIONAL MATCH (pkg:Virtual:Package)-[:CONTAINS]->(v)
RETURN v.fqn, count(DISTINCT stub) AS StubCount, pkg.fqn AS Package;
```

The `StubCount` should equal the number of scanned artifacts that referenced `java.util.List`, and `Package` should be `java.util`.

Confirm dependencies were lifted onto the same canonical node:

```cypher
MATCH (a)-[r:DEPENDS_ON]->(v:Virtual:Type {fqn: "java.util.List"})
WHERE NOT a:Virtual
RETURN count(DISTINCT a) AS Dependents, sum(r.weight) AS TotalWeight;
```

`Dependents` is the number of real nodes that depended on any stub of `java.util.List`; each now has a direct `:DEPENDS_ON` to the canonical `:Virtual:Type`.

## Operational notes

**Performance**. The MERGEs benefit from the indexes that jQAssistant's Java plugin already creates on `:Type(fqn)` and `:Package(fqn)` at store-init time (via descriptor annotations, not rule-level `CREATE CONSTRAINT`). Cypher's `CREATE CONSTRAINT ... FOR (...)` accepts only a single label, so a `:Virtual:Type` uniqueness constraint cannot be expressed as a rule — and isn't needed: the existing single-label indexes already give MERGE an index-backed lookup. The `:Virtual:Artifact` MERGE creates exactly one node so its lookup cost is irrelevant.

**Idempotency**. All concepts use `MERGE`, so re-running them produces no duplicate nodes or edges. They can safely be run after every scan.

**Interaction with `classpath:Resolve`**. The two are complementary. `classpath:Resolve` adds cross-artifact edges to fully-scanned types when those exist; this spec creates canonical virtual nodes when no fully-scanned version exists. Run both for the most complete graph: `classpath:Resolve` first (so the fully-scanned bridges are in place), then the virtual concepts.

**Cleanup**. To remove the virtual layer entirely:

```cypher
MATCH (v:Virtual) DETACH DELETE v;
```

This deletes `:Virtual:Type`, `:Virtual:Package`, and `:Virtual:Artifact` nodes along with all their edges — including the lifted `:DEPENDS_ON` edges from concept 5 and the lifted `:EXTENDS` / `:IMPLEMENTS` / `:ANNOTATED_BY` edges from concepts 6–8, all of which terminate on `:Virtual:Type` nodes. Original stubs and their own relationships are unaffected.

**Schema version**. The XML uses jQAssistant schema v2.9. Adjust the namespace and `xsi:schemaLocation` to match the installed jQAssistant version; mismatched versions are usually silently ignored rather than reported as errors.

## Open questions and possible extensions

- **Array element resolution**: a follow-up concept could parse FQNs starting with `[` and link the array to its element type's virtual node.
- **Inner class handling**: types like `com.foo.Outer$Inner` currently produce a `:Virtual:Type` with `name = "Outer$Inner"` and a package of `com.foo`. A refinement could split outer/inner relationships if useful.
- **Unifying with real `:Package` nodes**: an additional concept could merge `:Virtual:Package {fqn: "java.util"}` with a real `:Package {fqn: "java.util"}` if the JDK is ever scanned. The current design deliberately keeps them separate; revisit if cross-querying becomes painful.
- **Provenance**: if downstream queries need to know which artifacts referenced a virtual type, derive it on demand by traversing back through `:RESOLVES_TO` to the stubs and then to their containing `:Artifact` nodes.
