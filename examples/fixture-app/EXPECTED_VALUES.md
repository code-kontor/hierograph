# Expected Values: fixture-app

Canonical reference for test assertions in `frontend-testing` (#0021) and future backend/API tests.
All values are **verified by construction** via jQAssistant scan + Cypher calibration (step 5 of the
fixture design). Never reference nodes by Neo4j ID — IDs are unstable across scans.

> Rule: Row = Source, Column = Target in all DSM matrices.

---

## 1. Hierarchy

The tree as the frontend sees it (via GraphQL `hierarchicalGraph`).
`text` values are the canonical node identifiers (= `fqn` from the mapping).

```
[ROOT]
├── io.hierograph.examples:fixture-app:1.0.0          (java.module — Maven Project)
│   └── io.hierograph.examples:fixture-app:jar:1.0.0  (java.module — Main Artifact)
│       └── org                                        (java.package)
│           └── org.hg                                 (java.package)
│               └── org.hg.fixture                    (java.package)
│                   └── org.hg.fixture.basic           (java.package — class-less, only sub-packages)
│                       ├── org.hg.fixture.basic.core  (java.package)
│                       │   ├── org.hg.fixture.basic.core.AbstractBase        (java.class)
│                       │   ├── org.hg.fixture.basic.core.Color               (java.enum)
│                       │   ├── org.hg.fixture.basic.core.Contract            (java.interface)
│                       │   ├── org.hg.fixture.basic.core.Outer               (java.class, hasChildren=true)
│                       │   ├── org.hg.fixture.basic.core.Outer$Inner         (java.class)
│                       │   ├── org.hg.fixture.basic.core.Outer$StaticNested  (java.class)
│                       │   ├── org.hg.fixture.basic.core.PackagePrivateHolder (java.class)
│                       │   ├── org.hg.fixture.basic.core.Pair                (java.record)
│                       │   └── org.hg.fixture.basic.core.PlainClass          (java.class, hasChildren=true)
│                       ├── org.hg.fixture.basic.cycle (java.package — class-less)
│                       │   ├── org.hg.fixture.basic.cycle.alpha (java.package)
│                       │   │   └── org.hg.fixture.basic.cycle.alpha.CycleA  (java.class)
│                       │   ├── org.hg.fixture.basic.cycle.beta  (java.package)
│                       │   │   └── org.hg.fixture.basic.cycle.beta.CycleB   (java.class)
│                       │   └── org.hg.fixture.basic.cycle.gamma (java.package)
│                       │       └── org.hg.fixture.basic.cycle.gamma.CycleC  (java.class)
│                       ├── org.hg.fixture.basic.isolated (java.package)
│                       │   └── org.hg.fixture.basic.isolated.Island          (java.class)
│                       ├── org.hg.fixture.basic.jdk   (java.package)
│                       │   ├── org.hg.fixture.basic.jdk.JdkExtends           (java.class)
│                       │   ├── org.hg.fixture.basic.jdk.JdkFieldRef          (java.class)
│                       │   └── org.hg.fixture.basic.jdk.JdkImplements        (java.class)
│                       └── org.hg.fixture.basic.rel   (java.package — class-less)
│                           ├── org.hg.fixture.basic.rel.source (java.package)
│                           │   ├── org.hg.fixture.basic.rel.source.AnnotatedType  (java.class)
│                           │   ├── org.hg.fixture.basic.rel.source.ContractImpl   (java.class)
│                           │   ├── org.hg.fixture.basic.rel.source.FieldAccessor  (java.class)
│                           │   ├── org.hg.fixture.basic.rel.source.FieldTypeRef   (java.class)
│                           │   ├── org.hg.fixture.basic.rel.source.MethodInvoker  (java.class)
│                           │   └── org.hg.fixture.basic.rel.source.SubClass       (java.class)
│                           └── org.hg.fixture.basic.rel.target (java.package)
│                               ├── org.hg.fixture.basic.rel.target.BaseClass      (java.class)
│                               ├── org.hg.fixture.basic.rel.target.TargetA        (java.class)
│                               ├── org.hg.fixture.basic.rel.target.TargetB        (java.class)
│                               ├── org.hg.fixture.basic.rel.target.TargetContract (java.interface)
│                               ├── org.hg.fixture.basic.rel.target.TargetMarker   (java.annotation)
│                               └── org.hg.fixture.basic.rel.target.ValueHolder    (java.class)
└── External Types                                     (java.module — virtual)
    └── java                                           (external Virtual:Package)
        ├── java.io                                    (external Virtual:Package)
        │   └── java.io.Serializable                   (external.type)
        └── java.util                                  (external Virtual:Package)
            ├── java.util.List                         (external.type)
            └── java.util.TimerTask                    (external.type)
```

**Corner cases:**
- Class-less packages (only sub-packages, no own types): `org.hg.fixture.basic`, `org.hg.fixture.basic.cycle`, `org.hg.fixture.basic.rel`
- Dependency-free type: `org.hg.fixture.basic.isolated.Island` — only implicit `java.lang.Object` reference (dangling, invisible in the model)
- Inner class siblings: `Outer$Inner` and `Outer$StaticNested` appear directly under `org.hg.fixture.basic.core` (same level as `Outer`), not nested beneath it — bytecode stores all class files flat in the package
- `hasChildren=true` on types that declare methods or fields: at minimum `PlainClass` (4 fields + 4 methods), `Outer` (declares Inner + StaticNested as nested types via DECLARES)

---

## 2. Type inventory

| fqn | GraphQL kind | Java construct | Visibility |
|-----|-------------|----------------|------------|
| org.hg.fixture.basic.core.AbstractBase | java.class | abstract class | public |
| org.hg.fixture.basic.core.Color | java.enum | enum | public |
| org.hg.fixture.basic.core.Contract | java.interface | interface | public |
| org.hg.fixture.basic.core.Outer | java.class | class | public |
| org.hg.fixture.basic.core.Outer$Inner | java.class | inner class | public |
| org.hg.fixture.basic.core.Outer$StaticNested | java.class | static nested class | public |
| org.hg.fixture.basic.core.PackagePrivateHolder | java.class | class | package-private |
| org.hg.fixture.basic.core.Pair | java.record | record | public |
| org.hg.fixture.basic.core.PlainClass | java.class | class | public |
| org.hg.fixture.basic.cycle.alpha.CycleA | java.class | class | public |
| org.hg.fixture.basic.cycle.beta.CycleB | java.class | class | public |
| org.hg.fixture.basic.cycle.gamma.CycleC | java.class | class | public |
| org.hg.fixture.basic.isolated.Island | java.class | class | public |
| org.hg.fixture.basic.jdk.JdkExtends | java.class | class | public |
| org.hg.fixture.basic.jdk.JdkFieldRef | java.class | class | public |
| org.hg.fixture.basic.jdk.JdkImplements | java.class | class | public |
| org.hg.fixture.basic.rel.source.AnnotatedType | java.class | class | public |
| org.hg.fixture.basic.rel.source.ContractImpl | java.class | class | public |
| org.hg.fixture.basic.rel.source.FieldAccessor | java.class | class | public |
| org.hg.fixture.basic.rel.source.FieldTypeRef | java.class | class | public |
| org.hg.fixture.basic.rel.source.MethodInvoker | java.class | class | public |
| org.hg.fixture.basic.rel.source.SubClass | java.class | class | public |
| org.hg.fixture.basic.rel.target.BaseClass | java.class | class | public |
| org.hg.fixture.basic.rel.target.TargetA | java.class | class | public |
| org.hg.fixture.basic.rel.target.TargetB | java.class | class | public |
| org.hg.fixture.basic.rel.target.TargetContract | java.interface | interface | public |
| org.hg.fixture.basic.rel.target.TargetMarker | java.annotation | @interface | public |
| org.hg.fixture.basic.rel.target.ValueHolder | java.class | class | public |

**PlainClass member visibility** (verifies that member-level nodes appear):

| member signature | kind | visibility |
|----------------|------|------------|
| `public int publicField` | java.field | public |
| `protected int protectedField` | java.field | protected |
| `int packagePrivateField` | java.field | package-private |
| `private int privateField` | java.field | private |
| `public void publicMethod()` | java.method | public |
| `protected void protectedMethod()` | java.method | protected |
| `void packagePrivateMethod()` | java.method | package-private |
| `private void privateMethod()` | java.method | private |

---

## 3. Dependencies per package pair

All values **verified by Cypher** against the scanned store.
Flags are computed by the hierograph mapping layer (not stored as DEPENDS_ON properties):
- `is_extends`: EXISTS `(t1)-[:EXTENDS]->(t2)`
- `is_implements`: EXISTS `(t1)-[:IMPLEMENTS]->(t2)`
- `is_annotated_by`: EXISTS `(t1)-[:ANNOTATED_BY]->(:Annotation)-[:OF_TYPE]->(t2)` (or via RESOLVES_TO for external)
- `is_depends_on_other`: none of the above

### `rel.source` → `rel.target` (weight sum: **9**)

| source type | target type | construction | native edges | weight | flag |
|------------|------------|-------------|-------------|--------|------|
| AnnotatedType | TargetMarker | `@TargetMarker` on class | ANNOTATED_BY→OF_TYPE | 1 | is_annotated_by |
| ContractImpl | TargetContract | `implements TargetContract` | IMPLEMENTS | 1 | is_implements |
| FieldAccessor | ValueHolder | parameter type + WRITES + READS | param-OF_TYPE + WRITES + READS | 3 | is_depends_on_other |
| FieldTypeRef | TargetA | `private TargetA ref;` | DEPENDS_ON (field OF_TYPE) | 1 | is_depends_on_other |
| MethodInvoker | TargetB | `TargetB.ping()` | INVOKES | 1 | is_depends_on_other |
| SubClass | BaseClass | `extends BaseClass` | EXTENDS + implicit super() INVOKES | 2 | is_extends |
| **TOTAL** | | | | **9** | |

### `rel.target` → `rel.source` (weight sum: **0**)

No dependencies — documented intentional non-cycle pair. The DSM cell is absent (0-weight cells are not returned by the API).

### Cycle package pairs (all weight: **1**)

| source pkg | target pkg | source type | target type | weight |
|-----------|-----------|-------------|-------------|--------|
| cycle.alpha | cycle.beta | CycleA | CycleB | 1 |
| cycle.beta | cycle.gamma | CycleB | CycleC | 1 |
| cycle.gamma | cycle.alpha | CycleC | CycleA | 1 |

All three flags: `is_depends_on_other` (field reference, no extends/implements/annotation).

### `jdk` → external types

| source type | external type | weight | flag |
|------------|--------------|--------|------|
| JdkExtends | java.util.TimerTask | 2 | is_extends (EXTENDS + implicit super()) |
| JdkFieldRef | java.util.List | 1 | is_depends_on_other |
| JdkImplements | java.io.Serializable | 1 | is_implements |

### Intra-package `core` (diagonal weight: **3**)

| source type | target type | weight | note |
|------------|------------|--------|------|
| Outer | Outer$StaticNested | 1 | Outer declares StaticNested → bytecode reference |
| Outer | Outer$Inner | 1 | Outer declares Inner → bytecode reference |
| Outer$Inner | Outer | 1 | Inner holds synthetic `this$0` field of type Outer |
| **TOTAL** | | **3** | |

All other packages have **intra-package diagonal = 0** (isolated, jdk, cycle.alpha, cycle.beta, cycle.gamma, rel.source, rel.target).

---

## 4. DSM reference matrices

> Orderedline ordering (determined by FAS sorter, confirmed in step 8): see below.
> 0-weight cells are not returned by the GraphQL API — only non-zero cells appear.

### (a) Parent: `org.hg.fixture.basic.rel` — children: [source, target]

FAS order (DAG, no cycle — verified via GraphQL): **[source, target]**

|  | source | target |
|--|--------|--------|
| **source** | 0 | **9** |
| **target** | 0 | 0 |

Only non-zero cell: `row=0 (source), col=1 (target), value=9`

No SCCs at this level: `stronglyConnectedComponents = []`

### (b) Parent: `org.hg.fixture.basic.cycle` — children: [alpha, beta, gamma]

FAS order (cycle — verified via GraphQL): **[alpha, beta, gamma]**

|  | alpha | beta | gamma |
|--|-------|------|-------|
| **alpha** | 0 | 1 | 0 |
| **beta** | 0 | 0 | 1 |
| **gamma** | 1 | 0 | 0 |

Non-zero cells:
- `row=0 (alpha), col=1 (beta), value=1`
- `row=1 (beta), col=2 (gamma), value=1`
- `row=2 (gamma), col=0 (alpha), value=1` ← back-edge (SCC indicator)

**SCC:** exactly one SCC with nodes `{gamma, beta, alpha}` (order in response: gamma, beta, alpha),
`nodePositions=[0, 1, 2]` (all three positions in the orderedNodes array).

### (c) Parent: `org.hg.fixture.basic` — children: 5 packages

FAS order (verified via GraphQL): **[isolated(0), rel(1), cycle(2), jdk(3), core(4)]**

(Note: ordering is determined by the FAS sorter, not alphabetical.)

|  | isolated | rel | cycle | jdk | core |
|--|----------|-----|-------|-----|------|
| **isolated** | 0 | 0 | 0 | 0 | 0 |
| **rel** | 0 | **9** | 0 | 0 | 0 |
| **cycle** | 0 | 0 | **3** | 0 | 0 |
| **jdk** | 0 | 0 | 0 | 0 | 0 |
| **core** | 0 | 0 | 0 | 0 | **3** |

Non-zero cells (GraphQL API returns only these):
- `row=1 (rel), col=1 (rel), value=9` — rel diagonal
- `row=2 (cycle), col=2 (cycle), value=3` — cycle diagonal
- `row=4 (core), col=4 (core), value=3` — core diagonal

Diagonal semantics:
- `rel`: 9 — rel.source→rel.target (both within rel subtree)
- `cycle`: 3 — alpha→beta=1, beta→gamma=1, gamma→alpha=1 (all intra-cycle-subtree)
- `isolated`: 0
- `jdk`: 0 — jdk→external types are outside this subtree
- `core`: 3 — intra-core Outer/Inner effects

No cross-child edges → no off-diagonal non-zero values. No SCCs: `stronglyConnectedComponents = []`

---

## 5. External types

Virtual type/package inventory created by `hierograph:virtual-external`.

**Excluded** (invisible): primitives, arrays, `java.lang.*` (incl. `java.lang.annotation.*`) — hence `TargetMarker`'s meta-annotations (`@Retention`, `@Target`) do not produce virtual nodes.

| kind | fqn | note |
|------|-----|------|
| Virtual:Package | java | top-level, directly under External Types module |
| Virtual:Package | java.io | child of `java` |
| Virtual:Package | java.util | child of `java` |
| Virtual:Type (external.type) | java.io.Serializable | under java.io; lifted by IMPLEMENTS from JdkImplements |
| Virtual:Type (external.type) | java.util.List | under java.util; lifted from JdkFieldRef field type |
| Virtual:Type (external.type) | java.util.TimerTask | under java.util; lifted by EXTENDS from JdkExtends |

---

## 6. Known bytecode effects

These effects are **by design** — they are what make the verified weights differ from a naive count.

| effect | example | weight impact |
|--------|---------|---------------|
| Implicit `super()` call on `extends` | `SubClass extends BaseClass`, `JdkExtends extends TimerTask` | +1 INVOKES on top of EXTENDS → weight 2, not 1 |
| Synthetic `this$0` field in non-static inner class | `Outer$Inner` holds `this$0: Outer` | intra-core weight +1 (Inner→Outer) |
| Outer class references inner in bytecode | `Outer` declares `Inner` and `StaticNested` | intra-core weight +1 each (Outer→Inner, Outer→StaticNested) |
| Field descriptor counts even if field is unused | `FieldTypeRef.ref` (private, never read) | weight 1 for TargetA reference |
| Multiple access operations on same field | `FieldAccessor.readAndWrite(holder)`: param-type + WRITES + READS on `holder.value` | weight 3 for ValueHolder |
| `java.lang.*` excluded from virtual external | `java.lang.Object` (all classes), `java.lang.annotation.Retention`/`Target` on TargetMarker | zero virtual nodes for these |
| Static `inline` of primitive/String constants | not present in fixture (intentionally avoided) | would produce 0 cross-class edges for constant references |
