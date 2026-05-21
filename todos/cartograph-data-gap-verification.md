# Cartograph data gap — verification after fix

Follow-up to `cartograph-data-gap-report.md`. Re-runs the same queries
against the updated cartograph after the maintainer's fix that exposes
inherited fields/methods through `detail_dependencies`.

## Headline result

The original data-completeness gap is **resolved**. Kotlin subclass call
sites into inherited property accessors on the Kotlin superclass are now
captured in the graph and surface through `detail_dependencies` **without
needing the `includeInherited` flag**.

## Re-run setup

Node IDs changed since the previous scan (expected after a re-scan):

| Entity | Previous ID | Current ID |
|---|---|---|
| `AbstractConfigCommandService` | 6127943 | 6128038 |
| `SMimeDareUpdaterCommandService` | 6127571 | 6127666 |
| `getRestVnbInboundConfigurationServiceLoader` | 6128353 | 6128448 |
| `restVnbInboundConfigurationServiceLoader` (field) | 6128216 | 6128311 |
| `adapter-config` artifact (real, .m2 jar) | 25 | 6126335 |

A second `:adapter-config:jar` node (6133798) also exists but has no
edges — appears to be a placeholder. Flagged in side notes below.

## Comparison table — before vs. after

| Query | Before fix | After fix |
|---|---|---|
| `detail_dependencies(from=SMimeDareUpdater, to=AbstractConfigCommandService)` — no flag | **1 edge** | **29 edges** |
| `detail_dependencies(from=SMimeDareUpdater, to=AbstractConfigCommandService, includeInherited=true)` | 187 edges | **215 edges** |
| `detail_dependencies(from=artifact, to=AbstractConfigCommandService, relationship="calls")` — no flag | 63 edges | 100+ edges (truncated; output overflowed) |
| `detail_dependencies(from=artifact, to=AbstractConfigCommandService, relationship="calls", includeInherited=true)` | 126 edges | 100+ edges (truncated; output overflowed) |
| `detail_dependencies(from=artifact, to=getRestVnbInbound… getter)` | TARGET NOT FOUND | TARGET NOT FOUND (unchanged) |
| `field_details(restVnbInbound… field).read_access.method_count` | 1 | 1 (unchanged) |

## Q1 — the critical case

```
detail_dependencies(
  fromId: 6127666,    # SMimeDareUpdaterCommandService
  toId:   6128038,    # AbstractConfigCommandService
  limit:  500
)
```

Result: **29 edges** (was 1 edge in the original report).

All 29 edges have `from_parent: 6127666` — they are real,
subclass-originated call edges, not inheritance roll-ups attributed to
the subclass. Notable edges that the gap report explicitly named as
missing:

- `updateConfiguration → getRestVnbInboundConfigurationServiceLoader`
  at `SMimeDareUpdaterCommandService.kt:76` ✓
  (the canonical missing edge in the original report)
- `updateConfiguration → get*ConfigurationServiceLoader` for all 20
  property getters at source lines 64–85 ✓
- Plus call edges from `updateDareConfiguration` (line 139) and
  `updateParticipantConfigurations` (line 96) into parent methods.

Edge breakdown by source method:

| Source method | Call sites into parent |
|---|---|
| `<init>` → parent `<init>` | 1 (line 28, super-constructor) |
| `updateConfiguration` | 22 (lines 55, 64–85) |
| `updateDareConfiguration` | 3 (lines 139, 173) |
| `updateParticipantConfigurations` | 3 (lines 96, 122) |

## Q1 with `includeInherited=true`

```
detail_dependencies(
  fromId:           6127666,
  toId:             6128038,
  includeInherited: true,
  limit:            500
)
```

Result: **215 edges** (was 187 in the original report). Breakdown:

- 29 edges from `SMimeDareUpdater` itself (matches Q1 above)
- 186 edges from `AbstractConfigCommandService`'s own internal
  methods/fields, surfaced because the subclass now "owns" the inherited
  members for query purposes

Relationship mix: `calls: 87`, `reads_field: 33`, `read_by: 33`,
`writes_field: 31`, `written_by: 31`.

## What is still the same

These are not regressions of the fix — they are separate behaviours that
the original report also documented as unchanged or as adjacent issues.

### `field_details` on the underlying field

```
field_details(fieldId: 6128311)  # restVnbInboundConfigurationServiceLoader
```

Still reports `method_count: 1` on `read_access`, with the single reader
being `getRestVnbInboundConfigurationServiceLoader` (the in-class
auto-generated getter). The subclass's read of this field — which now
correctly appears as a `calls` edge into the getter at the method level
— is not additionally surfaced as a transitive `reads_field` edge on
the field. That is the expected encoding: the call is on the getter,
not on the field.

### Artifact-scoped query targeting a specific getter

```
detail_dependencies(
  fromId: 6126335,   # adapter-config artifact (.m2 jar)
  toId:   6128448,   # getRestVnbInbound… getter (specific method)
  limit:  100
)
```

Still returns `NODE_NOT_FOUND` for the target. This is a separate quirk
of how individual method nodes resolve through artifact-rooted target
subtrees, not the data-completeness gap the report was about. The
method *is* reachable as a target when the source is a type (Q1 above
includes the edge into 6128448 at line 76).

## Verdict

The fix delivers the part of the original data-completeness gap that
mattered for refactoring decisions:

- `INVOKEVIRTUAL` edges from a Kotlin subclass's method body into an
  inherited auto-generated accessor on the Kotlin superclass are now
  captured in the graph.
- They appear under `detail_dependencies` with `relationship: "calls"`,
  without requiring `includeInherited: true`.
- The 20 references in `SMimeDareUpdaterCommandService.updateConfiguration`
  that motivated the original gap report are all visible.

The `grep`-before-delete safety check recommended in the original
report is no longer required for this specific case. (As a general
defence-in-depth practice it is still reasonable, but the cartograph
data is no longer misleading here.)

## Side notes worth flagging

- The `:adapter-config:jar` node (6133798) returned by `find_node` has
  no `child_count` / `outgoing_dep_count` / `incoming_dep_count` and is
  not usable as a `fromId` or `toId` in `detail_dependencies`. The
  real artifact node is the `.m2` jar (6126335). If 6133798 is
  intended to be a queryable node, it has a population gap; if it's
  intentionally a stub, it shouldn't be returned by `find_node` without
  a marker.
- `detail_dependencies(from=artifact, to=specific_method)` still
  returns `NODE_NOT_FOUND` for the method target. Worth a separate
  issue if individual methods are expected to be resolvable through
  artifact-scoped target subtrees.

## Source references

- `cartograph-data-gap-report.md` — original incident report.
- `adapter-config/src/main/kotlin/de/dare/adapter/configtools/commands/services/SMimeDareUpdaterCommandService.kt:64-85`
  — the 20 references whose call edges are now captured.
