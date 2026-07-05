# Locations Scenarios: `org.hg.fixture.locations`

Reference for exploring and testing the **Locations tab** of the Dependency
Inspector (`DependencyDetailsPanel` — the two side-by-side Source/Target trees
with cross-marking). Companion to [EXPECTED_VALUES.md](EXPECTED_VALUES.md),
which covers the frozen `org.hg.fixture.basic.*` tenant; this file covers the
separate `org.hg.fixture.locations.*` tenant added for the Locations-tab
rework.

All values below are **verified against a live scan** (jQAssistant → MCP
GraphQL), except the per-edge *flag* column in §3, which follows the same
by-construction rules as EXPECTED_VALUES §3. Nodes are identified by `fqn`
(the `text` field) — never by Neo4j id (unstable across scans). Names are
shown relative to the tenant root `org.hg.fixture.locations`.

> Rule (same as DSM): **Row = Source, Column = Target.** The Locations tab's
> left tree is the Source subtree, the right tree is the Target subtree.

---

## 0. How to reach this view

1. In the tree, select `org.hg.fixture.locations`. Its DSM has two children,
   ordered **[app, lib]**:

   |  | app | lib |
   |--|-----|-----|
   | **app** | 0 | **25** |
   | **lib** | 0 | **7** |

   - `row=0 (app), col=1 (lib), value=25` — the cross-package cell this
     document is about. Selecting it opens the Locations tab with Source = `app`
     subtree, Target = `lib` subtree.
   - `row=1 (lib), col=1 (lib), value=7` — lib's internal coupling (diagonal),
     a bonus deeper cell (see §7). `app` diagonal and `lib → app` are both 0.

2. Click the `(app → lib)` cell. The Locations tab renders the two filtered
   trees (§4) and cross-marking is driven by clicking type nodes (§5).

**Aggregate facts for the `(app → lib)` cell** (verified):
- DSM weight `value = 25` (sum of native/bytecode edge weights).
- `dependencySet.size = 13` — the number of distinct **type → type**
  dependency edges (this is what the Usages tab paginates; all 13 fit on one
  page, all reported with edge `type = DEPENDS_ON`).

---

## 1. Fixture layout

Two sibling packages under the tenant root model the two sides of one DSM cell:
`app` is the **source** side (outgoing dependencies), `lib` the **target** side
(depended upon).

```
org.hg.fixture.locations
├── app                                     (SOURCE side)
│   ├── app.web
│   │   ├── OrderController      (java.class)   → Order, OrderService, Customer
│   │   ├── CustomerController   (java.class)   → CustomerApi, CustomerRepository, Customer
│   │   └── ReportController     (java.class)   → Order, ReportFormat, Auditable
│   ├── app.batch
│   │   └── app.batch.nightly
│   │       └── NightlyReportJob (java.class)   → AbstractReport, Order, Customer, OrderLine
│   └── app.support
│       └── LoggingHelper        (java.class)   → nothing under lib (JDK only)
└── lib                                     (TARGET side)
    ├── lib.order
    │   ├── Order                (java.class)      ← 3 sources
    │   ├── OrderService         (java.class)      ← 1 source
    │   └── lib.order.detail
    │       └── OrderLine        (java.class)      ← 1 source
    ├── lib.customer
    │   ├── Customer             (java.class)      ← 3 sources
    │   ├── CustomerApi          (java.interface)  ← 1 source (implements)
    │   └── CustomerRepository   (java.interface)  ← 1 source
    ├── lib.report
    │   ├── AbstractReport       (java.class)      ← 1 source (extends)
    │   └── ReportFormat         (java.enum)       ← 1 source
    └── lib.audit
        └── Auditable            (java.annotation) ← 1 source (annotation)
```

**What each element is there to exercise:**
- `NightlyReportJob` — widest fan-out (4 targets in 4 packages) and the deepest
  source (3 packages down). Drives deep predecessor-marking on the source tree.
- `Order`, `Customer` — highest fan-in (3 sources each, spanning `app.web` and
  the deep `app.batch.nightly`). Drives deep predecessor-marking on the target
  tree and multi-source highlighting.
- `OrderService`, `OrderLine`, `CustomerApi`, `CustomerRepository`,
  `AbstractReport`, `ReportFormat`, `Auditable` — single-source targets (fan-in
  1), including the deep-to-deep pair `NightlyReportJob → OrderLine`.
- `LoggingHelper` / `app.support` — **non-participant**: references only the
  JDK, so the whole `app.support` package is absent from the filtered source
  tree (§4). Confirms that non-participating siblings are hidden.
- Edge-kind variety: EXTENDS (`NightlyReportJob → AbstractReport`), IMPLEMENTS
  (`CustomerController → CustomerApi`), ANNOTATED_BY (`ReportController →
  Auditable`), plus field/parameter/return/invocation references throughout.

---

## 2. Type inventory

| fqn (under `org.hg.fixture.locations`) | GraphQL kind | Java construct |
|----------------------------------------|--------------|----------------|
| app.web.OrderController | java.class | class |
| app.web.CustomerController | java.class | class (implements CustomerApi) |
| app.web.ReportController | java.class | class (@Auditable) |
| app.batch.nightly.NightlyReportJob | java.class | class (extends AbstractReport) |
| app.support.LoggingHelper | java.class | class (non-participant) |
| lib.order.Order | java.class | class |
| lib.order.OrderService | java.class | class |
| lib.order.detail.OrderLine | java.class | class |
| lib.customer.Customer | java.class | class |
| lib.customer.CustomerApi | java.interface | interface |
| lib.customer.CustomerRepository | java.interface | interface |
| lib.report.AbstractReport | java.class | abstract class |
| lib.report.ReportFormat | java.enum | enum |
| lib.audit.Auditable | java.annotation | annotation |

---

## 3. Dependency matrix (`app → lib`, 13 type-pair edges)

`size = 13` distinct type → type edges. The **flag** follows EXPECTED_VALUES §3
rules (`is_extends` / `is_implements` / `is_annotated_by` / else
`is_depends_on_other`).

| source type | target type | construction | flag |
|-------------|-------------|--------------|------|
| app.web.OrderController | lib.order.Order | local var + field + return type + `getId()` | is_depends_on_other |
| app.web.OrderController | lib.order.OrderService | field + `new` + `submit()` | is_depends_on_other |
| app.web.OrderController | lib.customer.Customer | method parameter | is_depends_on_other |
| app.web.CustomerController | lib.customer.CustomerApi | `implements CustomerApi` | **is_implements** |
| app.web.CustomerController | lib.customer.CustomerRepository | field + ctor param + `load()` | is_depends_on_other |
| app.web.CustomerController | lib.customer.Customer | method return type | is_depends_on_other |
| app.web.ReportController | lib.order.Order | method parameter + `getId()` | is_depends_on_other |
| app.web.ReportController | lib.report.ReportFormat | field + enum constant access | is_depends_on_other |
| app.web.ReportController | lib.audit.Auditable | `@Auditable` on class | **is_annotated_by** |
| app.batch.nightly.NightlyReportJob | lib.report.AbstractReport | `extends AbstractReport` + `record()` | **is_extends** |
| app.batch.nightly.NightlyReportJob | lib.order.Order | field | is_depends_on_other |
| app.batch.nightly.NightlyReportJob | lib.customer.Customer | field | is_depends_on_other |
| app.batch.nightly.NightlyReportJob | lib.order.detail.OrderLine | method param + `getAmount()` | is_depends_on_other |

Fan-in per target (how many distinct sources reference it):

| target | fan-in | sources |
|--------|--------|---------|
| lib.order.Order | 3 | OrderController, ReportController, NightlyReportJob |
| lib.customer.Customer | 3 | OrderController, CustomerController, NightlyReportJob |
| lib.order.OrderService | 1 | OrderController |
| lib.order.detail.OrderLine | 1 | NightlyReportJob |
| lib.customer.CustomerApi | 1 | CustomerController |
| lib.customer.CustomerRepository | 1 | CustomerController |
| lib.report.AbstractReport | 1 | NightlyReportJob |
| lib.report.ReportFormat | 1 | ReportController |
| lib.audit.Auditable | 1 | ReportController |

---

## 4. What the Locations tab renders (filtered trees)

`filteredChildren` returns only descendants that participate in the
`app → lib` cell. This is exactly what each tree shows.

**Source tree (root `app`)** — 4 participating types in 2 leaf packages:

```
app
├── app.web
│   ├── ReportController
│   ├── OrderController
│   └── CustomerController
└── app.batch
    └── app.batch.nightly
        └── NightlyReportJob
```

`app.support` / `LoggingHelper` are **absent** (non-participant, verified).

**Target tree (root `lib`)** — 9 participating types in 4 packages:

```
lib
├── lib.order
│   ├── lib.order.detail
│   │   └── OrderLine
│   ├── OrderService
│   └── Order
├── lib.audit
│   └── Auditable
├── lib.report
│   ├── AbstractReport
│   └── ReportFormat
└── lib.customer
    ├── Customer
    ├── CustomerApi
    └── CustomerRepository
```

> Child ordering above is the server's response order, not alphabetical.

---

## 5. Cross-marking scenarios

Selecting node(s) in one tree calls `filteredDependencies`, which returns
`markedSourceIds` / `markedTargetIds` **including predecessors** — so every
ancestor package of a marked type is marked too (its path lights up). The
marked set also technically includes ancestors *above* the subtree root
(`lib`/`app` themselves, `org.hg.fixture.locations`, the artifact/module, the
graph root); those are never rendered because each tree is rooted at
`lib`/`app`. Below, only the rendered subtree is listed. **All results
verified against the scan.**

### 5a. Select a Source type → marked Target nodes

| selected source | marked target types | marked target packages (path) |
|-----------------|--------------------|-------------------------------|
| OrderController | Order, OrderService, Customer | lib.order, lib.customer |
| CustomerController | Customer, CustomerApi, CustomerRepository | lib.customer |
| ReportController | Order, ReportFormat, Auditable | lib.order, lib.report, lib.audit |
| NightlyReportJob | Order, Customer, OrderLine, AbstractReport | lib.order, lib.order.detail, lib.customer, lib.report |

(Plus the target root `lib`, always marked as an ancestor of any hit.)

Notable: `NightlyReportJob` lights up **four** packages including the deep
`lib.order.detail` — the widest highlight. `CustomerController` stays entirely
within `lib.customer` — the most localized.

### 5b. Select a Target type → marked Source nodes

| selected target | marked source types | marked source packages (path) |
|-----------------|--------------------|-------------------------------|
| Order | OrderController, ReportController, NightlyReportJob | app.web, app.batch, app.batch.nightly |
| Customer | OrderController, CustomerController, NightlyReportJob | app.web, app.batch, app.batch.nightly |
| OrderService | OrderController | app.web |
| OrderLine | NightlyReportJob | app.batch, app.batch.nightly |
| CustomerApi | CustomerController | app.web |
| CustomerRepository | CustomerController | app.web |
| AbstractReport | NightlyReportJob | app.batch, app.batch.nightly |
| ReportFormat | ReportController | app.web |
| Auditable | ReportController | app.web |

(Plus the source root `app`, always marked.)

Notable: `Order` and `Customer` (fan-in 3) mark sources across **both** source
branches, lighting up the full deep path `app.batch → app.batch.nightly`.
`OrderLine` marks a single deep source and its deep ancestor chain — the
cleanest test of deep predecessor marking with exactly one hit.

### 5c. Multi-select on the Source tree

Selecting `OrderController` **and** `CustomerController` together marks the
**union** of their targets: Order, OrderService, Customer, CustomerApi,
CustomerRepository (packages lib.order + lib.customer). Verified.

### 5d. Edge-kind agnostic

Marking ignores the edge kind: `NightlyReportJob → AbstractReport` (EXTENDS),
`CustomerController → CustomerApi` (IMPLEMENTS) and `ReportController →
Auditable` (ANNOTATED_BY) mark their counterparts exactly like an ordinary
field/parameter reference.

---

## 6. Package selection — current behavior vs. open question

**Verified current behavior:** selecting a **package** node (e.g. `app.web`,
`lib.order`, or a tree root) marks **nothing** — `filteredDependencies` returns
empty `markedSourceIds`/`markedTargetIds`. Dependency edges have only
type-level endpoints, so a package id matches no edge. The panel passes the
clicked node's id straight through (its header even reads "click a **type** to
mark its counterparts").

This is a key case for the rework. Two candidate target behaviors to explore:

- **Expand to descendants:** selecting `app.web` marks the union of the
  counterparts of every type under it — i.e. Order, OrderService, Customer,
  CustomerApi, CustomerRepository, ReportFormat, Auditable (everything the
  three controllers touch; **not** OrderLine or AbstractReport, which only
  `NightlyReportJob` reaches). Selecting `lib.order` would mark
  OrderController, ReportController, NightlyReportJob (every source touching
  anything under lib.order — all except CustomerController).
- **Keep type-only:** package rows are structural/expandable only and never
  drive marking (today's behavior), possibly reflected in the UI by making
  package rows non-selectable.

The fixture is built so the "expand to descendants" union is non-trivial and
distinct from any single type's marks, so either choice is observable here.

---

## 7. Bonus: deeper cell `lib → lib` (intra-lib coupling)

Selecting the `lib` tree node exposes a DSM over lib's children; `OrderService`
carries intra-package edges (`→ Order`, `→ OrderLine`), giving the `lib.order`
subtree its own non-zero cell (aggregate `lib → lib` weight = **7**). Useful for
testing the Locations tab on a **diagonal** cell (Source subtree == Target
subtree, both rooted at `lib.order` / `lib`), where a node can appear on both
sides.

---

## 8. Regenerating

Source is the versioned representation; the store and recorded fixtures are
derived. After changing these classes:

1. `cd hierograph/examples/fixture-app && mvn clean install -Pjqassistant`
2. serve the store, start the MCP server (see
   [../../../docs/server-in-sessions-starten-stoppen.md](../../../docs/server-in-sessions-starten-stoppen.md)),
3. `cd hierograph/hierograph-web && pnpm fixtures:record`.

Do **not** modify `org.hg.fixture.basic.*` (frozen); keep new cases in this
`locations` tenant or another new one.
