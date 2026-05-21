# Cartograph data gap — incident report

## Summary

While refactoring `AbstractConfigCommandService` (Kotlin, Spring), I deleted
26 `@Autowired lateinit var` properties on the basis of a cartograph query
that showed *no* call edges from any subclass into those properties' getters.
The query was wrong: seven subclasses reference those properties extensively
in source (~60 call sites across the module). Deleting the fields broke the
build.

This report identifies the specific tool/query that returned the misleading
picture, presents the evidence that pins it as a **data-completeness** gap
rather than a query-filter gap, and recommends a workflow change.

## The query I used

```
mcp__cartograph__detail_dependencies(
  fromId:        25,        # Maven artifact: adapter-config
  toId:          6127943,   # AbstractConfigCommandService
  relationship:  "calls",
  limit:         500
)
```

Result: 63 edges total. 55 were intra-class getter calls (inside
`AbstractConfigCommandService` itself); 8 were `<init>` super-constructor
calls from the 8 subclasses. **Zero** edges from any subclass method into
any loader getter — despite the fact that, for example,
`SMimeDareUpdaterCommandService.updateConfiguration()` reads 20 such
properties between source lines 64 and 85.

## Verification — it is a data-completeness gap, not a query-filter gap

I ran three follow-up cartograph queries to determine whether the missing
edges exist in the graph under a different relationship kind, or are simply
not in the data at all.

### Q1 — No relationship filter

```
mcp__cartograph__detail_dependencies(
  fromId: 6127571,   # SMimeDareUpdaterCommandService
  toId:   6127943,   # AbstractConfigCommandService
  limit:  500
)
```

Result:

```
summary.total_edges:    1
summary.by_relationship: { "calls": 1 }
```

The only edge present at all between these two classes is the
`<init> → <init>` super-constructor call. No edge of any other kind
(`reads_field`, `read_by`, `has_type`, etc.) is present.

⇒ The expected getter-call edges are **not present in the graph under any
relationship kind**.

### Q2 — Targeted incoming on the specific getter

After locating the getter via
`list_methods(typeId: 6127943, namePattern: "getRestVnbInbound")` →
method id `6128353` (`getRestVnbInboundConfigurationServiceLoader`):

```
mcp__cartograph__detail_dependencies(
  fromId: 25,            # adapter-config artifact (any caller)
  toId:   6128353,       # the getter
  limit:  100
)
```

Result: `TARGET NODE NOT FOUND`. The getter is enumerable via
`list_methods` but has no materialised incoming edges in the dependency
graph — it cannot be used as a target.

### Q3 — Sanity check: the subclass *does* emit `calls` edges in general

```
mcp__cartograph__detail_dependencies(
  fromId:        6127571,   # SMimeDareUpdaterCommandService
  toId:          25,        # adapter-config artifact (any target)
  relationship:  "calls",
  limit:         50
)
```

Result: 72 `calls` edges, all from `updateConfiguration` and friends to
other methods (certificates, secrets, configurations) in the same module.

⇒ The subclass *is* fully scanned and *does* emit `calls` edges; the
specific edges into inherited Kotlin-synthesised getters are missing.

## Verdict

**Data-completeness gap.** The jQAssistant scan that populates cartograph's
graph did not capture call sites where a Kotlin subclass invokes a
property-access getter that is auto-generated on its Kotlin superclass.
The edges are absent under every relationship kind, and the target getter
methods are not even queryable as targets in `detail_dependencies`.

Implications:

- The originally-cited query
  (`detail_dependencies` with `relationship: "calls"`) is the one I used,
  but it is **not uniquely at fault**. Every cartograph tool that draws
  from this data — `detail_dependencies`, `outgoing_to`, `incoming_from`,
  `aggregated_outgoing`, `aggregated_incoming`, `field_details.read_access`,
  `method_details` (incoming) — would have produced the same misleading
  picture.
- The blind spot is specific to **Kotlin properties with auto-generated
  accessors**, accessed from a subclass through inheritance. Java field
  access and explicit method calls in either language appear to be
  captured normally.

## Recommendation for future cartograph use

For *any* refactor that deletes a property or field on a Kotlin
superclass (or any `lateinit var` / `val` with auto-generated accessors):

1. Use cartograph to identify candidate consumers and shape the refactor —
   it remains useful for that.
2. **Always** cross-check with a plain text search before deletion:
   ```
   grep -r '<propertyName>' <module>/src
   ```
   For this incident, a single such grep would have surfaced the 20
   references in `SMimeDareUpdaterCommandService.kt` (plus references in
   six other subclasses) in under a second.
3. Treat cartograph's "no incoming edges" result on a Kotlin getter as
   *unverified* rather than *definitive*. Verify with source.

For non-Kotlin code (plain Java, no auto-generated accessors) the
underlying data appears complete; this caveat does not apply.

## Update — partial fix applied

Cartograph was updated so that `detail_dependencies` honours an
`includeInherited: true` flag that surfaces inherited members of the
source/target subtree. Re-running the queries confirms the flag now works
correctly, but **the specific gap described above is still open**.

### What the fix gives you

| Query | Before fix | After fix |
|---|---|---|
| `detail_dependencies(from=25, to=6127943, relationship="calls")` | 63 edges | 63 edges (unchanged) |
| `detail_dependencies(from=6127571, to=6127943)` (no flag) | 1 edge | 1 edge (unchanged) |
| `detail_dependencies(from=6127571, to=6127943, includeInherited=true)` | n/a | **187 edges** |
| `detail_dependencies(from=25, to=6127943, relationship="calls", includeInherited=true)` | n/a | **126 edges** |
| `field_details(6128216).read_access.method_count` | 1 | 1 (unchanged) |

The 187-edge result for the inherited-aware query is a meaningful
improvement: the subclass now "owns" the inherited methods and fields of
its parent for query purposes, so structural questions like "what
methods/fields does this subclass surface?" return the full picture.

### What the fix does *not* give you

In the 187-edge result, `by_source_type` is
`{ 6127943: 186, 6127571: 1 }`. Of the 186 new edges, **every one** has
`from_parent: 6127943` — they represent inherited members attributed to
the subclass, not new call-site edges originating in the subclass's own
code. The only edge whose `from_parent: 6127571` is still the
`<init> → <init>` super-constructor call.

Concretely, the call edge
`SMimeDareUpdaterCommandService.updateConfiguration → AbstractConfigCommandService.getRestVnbInboundConfigurationServiceLoader`
(method id `6127576 → 6128353`) still does not appear in the graph under
any tool or relationship kind. `field_details(6128216).read_access` still
reports a single reader (the in-class getter).

### Net assessment

The fix is real and useful for inheritance-aware structural queries, but
the original data-completeness gap is unchanged for our use case: when a
Kotlin subclass invokes an inherited property getter, the call-site
`INVOKEVIRTUAL` edge from the subclass's method body into the parent
class's auto-generated accessor is not captured. The recommendation above
still stands — always `grep` before deleting a Kotlin property with
auto-generated accessors.

## Upstream

Worth filing an issue with the cartograph / jQAssistant maintainers:
bytecode-level `INVOKEVIRTUAL` edges from a subclass method into a
superclass's Kotlin-synthesised property accessor are not captured in
the dependency graph (verified after the `includeInherited` fix).

## Source references

- `adapter-config/src/main/kotlin/de/dare/adapter/configtools/commands/services/SMimeDareUpdaterCommandService.kt:64-85`
  — the 20 missed references that motivated this report.
- `adapter-config/src/main/kotlin/de/dare/adapter/configtools/commands/services/AbstractMessagingInterfaceBasedCommandService.kt`,
  `…/E2ETestConfigurationsUpdaterService.kt`,
  `…/SMimeCplusUpdaterService.kt`,
  `…/TlsServerCertificateDareUpdaterService.kt`,
  `…/TlsServerKeystoreDareUpdaterService.kt`,
  `…/TlsClientCertificateCplusUpdaterService.kt`
  — six further subclasses with the same property-access pattern.
