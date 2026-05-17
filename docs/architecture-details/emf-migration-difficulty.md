# EMF to Kotlin Migration Difficulty Assessment

See also: [EMF Features Used in Hierarchicalgraph Core Model](emf-features-used.md)

## Easy to Replace

**EMap usage** -- Used purely as caches in `ExtendedHGNodeTrait`. Direct replacement with
`MutableMap<HGNode, HGAggregatedDependency>`.

**EcoreUtil** -- Only used for `getAllContents()` tree traversal in `ExtendedHGRootNodeImpl`.
Replace with a recursive walk over `node.children`.

**Generated boilerplate (73% of code)** -- The generated impl classes (`HGNodeImpl`,
`HGRootNodeImpl`, etc.) are mostly getters/setters/reflection. Kotlin data classes or plain
classes replace this trivially.

**External API surface** -- No code outside `core.model` uses EMF-specific types (no `EObject`
casts, no `eAdapters()` calls). Public interfaces are cleanly abstracted. Migration is invisible
to consumers.

## Moderate Effort

**Bidirectional references** -- Only 3-4 pairs: `parent`/`children`, `nodeSource`/`node`,
`dependencySource`/`dependency`. Replace with custom setters that maintain both sides.
Straightforward but needs care.

**Notifications / Adapters** -- Used for property-change style events (e.g., weight changes in
`ExtendedHGAggregatedDependencyImpl`, resolved-state in `ExtendedHGProxyDependencyImpl`). Not
cascading. A simple Kotlin listener/observer pattern or `Flow`/`StateFlow` works well.

## Hard Part

**EList with notification-driven cache invalidation** -- This is the main blocker. The model
relies on `EObjectEList` (and a custom `EObjectEListWithoutUniqueCheck` that allows duplicate
edges) to **automatically fire notifications when dependencies are added/removed**. These
notifications cascade into cache invalidation across `ExtendedHGNodeTrait` (608 lines of caching
logic).

Concretely:
- `dependency.getFrom().getOutgoingCoreDependencies().remove(dependency)` triggers a list-change
  notification
- That notification invalidates cached aggregated dependency maps
- 5 lists use this pattern: incoming/outgoing core dependencies, accumulated incoming/outgoing,
  and aggregated core dependencies

Replacing this requires building an **observable list** in Kotlin that fires change events, or
redesigning cache invalidation to be explicit rather than reactive. The logic in
`ExtendedHGNodeTrait` would need substantial rework either way.

## Summary

| Aspect | Difficulty | Lines affected |
|---|---|---|
| EMap caches | Trivial | ~30 lines |
| EcoreUtil traversal | Easy | ~10 lines |
| Generated boilerplate | Easy | ~6,100 lines disappear |
| External consumers | None | 0 |
| Bidirectional refs | Moderate | ~60 lines |
| Notifications | Moderate | ~50 lines |
| **EList + cache invalidation** | **Hard** | **~600 lines in ExtendedHGNodeTrait** |

## Bottom Line

The migration is very feasible overall. The one area requiring real design work is replacing the
reactive EList-driven cache invalidation in `ExtendedHGNodeTrait` -- everything else maps cleanly
to idiomatic Kotlin.
