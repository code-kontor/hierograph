# Implementation Spec: Virtual Canonical Nodes for Unparsed Types and Packages

## Overview

jQAssistant's Java scanner creates per-artifact stub `:Type` nodes for any class referenced in bytecode but not itself parsed. When the same external type (e.g. `java.util.List`) is referenced by N scanned artifacts, N independent stub nodes exist in the graph — each with only `fqn` set, no `byteCodeVersion`, and signature-only `:Method`/`:Field` children. There are no `:Package` nodes for the packages these types belong to, because no `.class` file was scanned to create them.

The built-in `classpath:Resolve` concept solves an adjacent problem (linking references across scanned artifacts to fully-scanned counterparts) but does nothing for the all-stub case. It also leaves the duplicate stubs in place.

This spec defines a set of jQAssistant concepts that produce **canonical virtual nodes** — one `:Virtual:Type` per external FQN and one `:Virtual:Package` per package FQN — without modifying or deleting the original stubs. Stubs are linked to their canonicals via `:RESOLVES_TO`. Packages contain their types and their child packages via `:CONTAINS`.

## Goals

- One canonical node per external type FQN, queryable as `:Virtual:Type`.
- One canonical node per external package FQN, queryable as `:Virtual:Package`.
- Full package hierarchy (`com` → `com.acme` → `com.acme.foo`) materialized as `:CONTAINS` edges.
- Non-destructive: original stubs and their relationships are untouched.
- Idempotent: re-running the concepts does not duplicate nodes or edges.
- Optional bridging to fully-scanned types when one exists for the same FQN.

## Non-goals

- Replacing or rewriting `:DEPENDS_ON`, `:INVOKES`, `:READS`, `:WRITES`, or other edges on the original stubs. Traversals that want the canonical view add one extra `:RESOLVES_TO` hop.
- Inferring members (methods, fields) of external types beyond what the bytecode references already produced as signature stubs.
- Merging virtual nodes with real `:Package` nodes created by the Java scanner. The `:Virtual` label keeps them distinct.

## Design decisions

### Per-artifact stubs are kept

Each stub retains its place in the artifact that referenced it. This preserves per-artifact dependency provenance ("artifact A references `java.util.List`, artifact B does not"). The canonical node is *additional*, not a replacement.

### Separate `:Virtual` label

Canonical nodes carry `:Virtual` in addition to `:Type` or `:Package`. This prevents accidental matches by queries written against the real Java model and makes the virtual layer opt-in. Queries that want the unified view explicitly match on the virtual label.

### `:RESOLVES_TO` for stub→canonical links

Reuses the relationship type already established by `classpath:Resolve` for "this reference points at a richer representation." A query that follows `(:Type)-[:RESOLVES_TO*]->(target)` works uniformly whether `target` is a fully-scanned type from `classpath:Resolve` or a virtual node from this spec.

### Exclusions

The following are deliberately excluded from virtual-type creation:

- **Primitives**: `byte`, `short`, `int`, `long`, `char`, `float`, `double`, `boolean`, `void`.
- **JVM array descriptors**: any FQN starting with `[` (e.g. `[D`, `[Ljava/lang/String;`).
- **Default-package types**: any FQN with no `.` (these are rare and usually represent edge cases not worth a canonical node).

Array element types can be derived in a separate concept if needed; this spec doesn't cover that.

## Prerequisites

Create uniqueness constraints before running the concepts. Without them, every `MERGE` does a label scan and the concepts get progressively slower as the graph grows.

```cypher
CREATE CONSTRAINT virtual_type_fqn IF NOT EXISTS
FOR (v:Virtual:Type) REQUIRE v.fqn IS UNIQUE;

CREATE CONSTRAINT virtual_package_fqn IF NOT EXISTS
FOR (p:Virtual:Package) REQUIRE p.fqn IS UNIQUE;
```

These can be created manually in the Neo4j browser or via a setup concept that runs once.

## Concepts

### 1. `my-rules:VirtualExternalType`

Creates a `:Virtual:Type` node for every unparsed type stub, keyed by FQN, and links each stub to it.

**Inputs**: existing `:Type` nodes with no `byteCodeVersion`, excluding primitives, arrays, and default-package names.

**Outputs**:
- One `:Virtual:Type {fqn, name}` node per distinct external FQN.
- One `(:Type)-[:RESOLVES_TO]->(:Virtual:Type)` edge per stub.

**Cypher**:

```cypher
MATCH (t:Type)
WHERE t.byteCodeVersion IS NULL
  AND NOT t.fqn IN ["byte","short","int","long","char","float","double","boolean","void"]
  AND NOT t.fqn STARTS WITH "["
  AND t.fqn CONTAINS "."
WITH t, t.fqn AS fqn
MERGE (v:Virtual:Type {fqn: fqn})
  ON CREATE SET v.name = split(fqn, ".")[-1]
MERGE (t)-[:RESOLVES_TO]->(v)
RETURN count(DISTINCT v) AS VirtualTypes
```

### 2. `my-rules:VirtualExternalPackage`

Creates a `:Virtual:Package` node for each virtual type's package and links package to type.

**Depends on**: `my-rules:VirtualExternalType`.

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

### 3. `my-rules:VirtualPackageHierarchy`

Builds the parent-child hierarchy between virtual packages by walking each FQN's dotted path and materializing every ancestor.

**Depends on**: `my-rules:VirtualExternalPackage`.

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

### 4. (Optional) `my-rules:VirtualResolvesToParsed`

Bridges virtual types to their fully-scanned counterparts when both exist. Enables uniform traversal: stub → virtual → fully-scanned.

**Depends on**: `my-rules:VirtualExternalType`.

**Inputs**: `:Virtual:Type` nodes; fully-scanned `:Type` nodes (those with `byteCodeVersion`).

**Outputs**:
- One `(:Virtual:Type)-[:RESOLVES_TO]->(:Type)` edge per FQN match.

**Cypher**:

```cypher
MATCH (v:Virtual:Type), (real:Type)
WHERE v.fqn = real.fqn
  AND real.byteCodeVersion IS NOT NULL
MERGE (v)-[:RESOLVES_TO]->(real)
RETURN count(*) AS Linked
```

Include this only when fully-scanned versions of external types may exist in the graph (e.g. JDK classes after a deliberate JDK scan, or library JARs included via `maven3.dependencies.scan`).

## Rule file

Save as `jqassistant/virtual-external.xml` in the project root:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jqassistant-rules xmlns="http://schema.jqassistant.org/rule/v2.9"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://schema.jqassistant.org/rule/v2.9
                                       https://jqassistant.github.io/jqassistant/current/schema/jqassistant-rule-v2.9.xsd">

  <group id="virtual-external">
    <includeConcept refId="my-rules:VirtualExternalType"/>
    <includeConcept refId="my-rules:VirtualExternalPackage"/>
    <includeConcept refId="my-rules:VirtualPackageHierarchy"/>
    <!-- Uncomment if fully-scanned external types may exist:
    <includeConcept refId="my-rules:VirtualResolvesToParsed"/>
    -->
  </group>

  <concept id="my-rules:VirtualExternalType">
    <description>
      For every unparsed (stub) :Type referenced in the graph, create a canonical :Virtual:Type
      node keyed by fqn, and link each stub to it via :RESOLVES_TO. Primitives, JVM array
      descriptors, and default-package types are excluded.
    </description>
    <cypher><![CDATA[
      MATCH (t:Type)
      WHERE t.byteCodeVersion IS NULL
        AND NOT t.fqn IN ["byte","short","int","long","char","float","double","boolean","void"]
        AND NOT t.fqn STARTS WITH "["
        AND t.fqn CONTAINS "."
      WITH t, t.fqn AS fqn
      MERGE (v:Virtual:Type {fqn: fqn})
        ON CREATE SET v.name = split(fqn, ".")[-1]
      MERGE (t)-[:RESOLVES_TO]->(v)
      RETURN count(DISTINCT v) AS VirtualTypes
    ]]></cypher>
  </concept>

  <concept id="my-rules:VirtualExternalPackage">
    <requiresConcept refId="my-rules:VirtualExternalType"/>
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

  <concept id="my-rules:VirtualPackageHierarchy">
    <requiresConcept refId="my-rules:VirtualExternalPackage"/>
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

  <concept id="my-rules:VirtualResolvesToParsed">
    <requiresConcept refId="my-rules:VirtualExternalType"/>
    <description>
      If a fully-scanned :Type with the same FQN exists, link the :Virtual:Type to it via
      :RESOLVES_TO, enabling uniform traversal from stub to virtual to fully-scanned type.
    </description>
    <cypher><![CDATA[
      MATCH (v:Virtual:Type), (real:Type)
      WHERE v.fqn = real.fqn
        AND real.byteCodeVersion IS NOT NULL
      MERGE (v)-[:RESOLVES_TO]->(real)
      RETURN count(*) AS Linked
    ]]></cypher>
  </concept>

</jqassistant-rules>
```

## Activation

Activate the `virtual-external` group on the command line:

```bash
jqassistant.sh analyze --groups virtual-external
```

Or via `.jqassistant.yml`:

```yaml
jqassistant:
  analyze:
    groups:
      - virtual-external
```

Or via Maven property:

```bash
mvn jqassistant:analyze -Djqassistant.analyze.groups=virtual-external
```

Because of the `requiresConcept` chain, the group only needs to include the leaf concept(s); dependencies pull in their prerequisites automatically. The group above lists all three for discoverability.

## Verification

After running, confirm the concepts produced output:

```cypher
MATCH (v:Virtual:Type) RETURN count(v) AS VirtualTypes;
MATCH (p:Virtual:Package) RETURN count(p) AS VirtualPackages;
MATCH (:Type)-[r:RESOLVES_TO]->(:Virtual:Type) RETURN count(r) AS StubLinks;
MATCH (:Virtual:Package)-[r:CONTAINS]->(:Virtual:Type) RETURN count(r) AS PackageContents;
MATCH (:Virtual:Package)-[r:CONTAINS]->(:Virtual:Package) RETURN count(r) AS PackageHierarchy;
```

Sanity-check a known external type, e.g.:

```cypher
MATCH (v:Virtual:Type {fqn: "java.util.List"})
OPTIONAL MATCH (stub:Type)-[:RESOLVES_TO]->(v)
OPTIONAL MATCH (pkg:Virtual:Package)-[:CONTAINS]->(v)
RETURN v.fqn, count(DISTINCT stub) AS StubCount, pkg.fqn AS Package;
```

The `StubCount` should equal the number of scanned artifacts that referenced `java.util.List`, and `Package` should be `java.util`.

## Operational notes

**Performance**. With the uniqueness constraints in place, the concepts scale roughly linearly with the number of stub nodes. On graphs with hundreds of thousands of stubs, expect total runtime in the low tens of seconds. Without the constraints, runtime grows quadratically and becomes impractical past a few thousand stubs.

**Idempotency**. All three concepts use `MERGE`, so re-running them produces no duplicate nodes or edges. They can safely be run after every scan.

**Interaction with `classpath:Resolve`**. The two are complementary. `classpath:Resolve` adds cross-artifact edges to fully-scanned types when those exist; this spec creates canonical virtual nodes when no fully-scanned version exists. Run both for the most complete graph: `classpath:Resolve` first (so the fully-scanned bridges are in place), then the virtual concepts. If concept 4 (`VirtualResolvesToParsed`) is enabled, it provides a uniform second-hop bridge that also covers the cases `classpath:Resolve` handled.

**Cleanup**. To remove the virtual layer entirely:

```cypher
MATCH (v:Virtual) DETACH DELETE v;
```

This deletes both `:Virtual:Type` and `:Virtual:Package` nodes along with all their edges. Original stubs and their relationships are unaffected.

**Schema version**. The XML uses jQAssistant schema v2.9. Adjust the namespace and `xsi:schemaLocation` to match the installed jQAssistant version; mismatched versions are usually silently ignored rather than reported as errors.

## Open questions and possible extensions

- **Array element resolution**: a follow-up concept could parse FQNs starting with `[` and link the array to its element type's virtual node.
- **Inner class handling**: types like `com.foo.Outer$Inner` currently produce a `:Virtual:Type` with `name = "Outer$Inner"` and a package of `com.foo`. A refinement could split outer/inner relationships if useful.
- **Unifying with real `:Package` nodes**: an additional concept could merge `:Virtual:Package {fqn: "java.util"}` with a real `:Package {fqn: "java.util"}` if the JDK is ever scanned. The current design deliberately keeps them separate; revisit if cross-querying becomes painful.
- **Provenance**: if downstream queries need to know which artifacts referenced a virtual type, derive it on demand by traversing back through `:RESOLVES_TO` to the stubs and then to their containing `:Artifact` nodes.
