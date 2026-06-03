# Hierograph Graph Serialization — Design

This module (`io.hierograph.hierarchicalgraph.serialization`) provides a flat,
ID‑keyed JSON serializer for `HGRootNode` instances using Jackson.

The HG model has no built-in serialization: nodes hold cyclic `parent ↔ children`
and `from/to ↔ outgoing/incoming` references, the `INodeSource` / `IDependencySource`
SPIs are polymorphic, and several private caches on `HGNodeImpl`
(`_predecessors`, `_accumulatedOutgoing/Incoming`, `_cachedAggregatedOutgoing/Incoming`)
would either bloat output or need transient exclusions. A generic object-graph
serializer would have to be heavily configured to cope with all of this.

Instead, this module projects the graph to a flat record stream — nodes by id,
core dependencies referencing nodes by id — and round-trips it via
`HierarchicalGraphFactory`. Caches are derived and rebuild lazily after load.

## Public API

A single entry-point object, `HGGraphJson`. Everything else is in the
`.internal` subpackage and is not part of the public API.

```kotlin
object HGGraphJson {
    fun write(root: HGRootNode, prettyPrint: Boolean = false): String
    fun write(root: HGRootNode, sink: OutputStream, prettyPrint: Boolean = false)

    fun read(json: String): HGRootNode
    fun read(source: InputStream): HGRootNode
}
```

Out of the box, graphs whose nodes / dependencies use any of the following
sources round-trip:

- `DefaultNodeSource` / `DefaultDependencySource` — round-trip into the same
  type, preserving `properties` and identifier type.
- `GraphDbRootNodeSource` / `GraphDbNodeSource` / `GraphDbDependencySource` —
  round-trip into `DefaultNodeSource` / `DefaultDependencySource` (no Bolt at
  read time). v1 stores **identifier only** — lazy `labels` / `properties`
  are NOT force-loaded from Neo4j on write, and `GraphDbDependencySource.type`
  is dropped (the relationship type is already carried by `HGCoreDependency.type`
  and stored on the `DepRecord`).

Other `INodeSource` / `IDependencySource` impls fail fast on `write`. v1 does
not expose a hook to register custom codecs; promote the internal registry to
public API when that need arises.

`read` rejects snapshots whose `schemaVersion` differs from the current
version with an `IllegalArgumentException`.

## Wire format (internal)

The on-disk shape, written by Jackson via the internal data classes:

```kotlin
internal data class GraphSnapshot(
    val schemaVersion: Int = 1,
    val root: NodeRecord,
    val nodes: List<NodeRecord>,    // every non-root node, parents before children
    val deps:  List<DepRecord>      // every core dependency, deduplicated by identity
)

internal data class NodeRecord(
    val id: String,                 // String form of nodeSource.identifier
    val parentId: String?,          // null only for the root
    val kind: KindRef?,             // null when HGNode.kind is null
    val source: SourceRef
)

internal data class DepRecord(
    val id: String,                 // String form of dependencySource.identifier
    val fromId: String,
    val toId:   String,
    val type:   String,             // HGCoreDependency.type, e.g. "DEPENDS_ON"
    val weight: Int,
    val attributesBitmap: Int,      // raw bits; decode via JavaEdgeAttributes
    val source: SourceRef
)

internal data class KindRef(val type: String, val value: String)
internal data class SourceRef(val type: String, val payload: Map<String, String> = emptyMap())
```

Design choices baked in:

- **IDs as `String`.** `HGNode.identifier` / `HGCoreDependency.dependencySource.identifier`
  are `Any`. In practice they're `Long` (graphdb) or a Kotlin-minted `Long`
  (`DefaultNodeSource(identifier = nextId++)`); String identifiers appear in
  some tests. Stringifying gives a stable wire form; per-source codecs own the
  coercion back.
- **`KindRef` carries `(type, value)`.** `HGNode.kind` is `Any?` and in this
  project is almost always a `JavaNodeKind`. The pair stores the fully
  qualified class name and the string form, so any enum kind round-trips
  without the serialization module having to know about the enum class.
- **`SourceRef` is a discriminated union of `Map<String, String>`.** Anything
  richer would force the snapshot to know every `INodeSource` / `IDependencySource`
  impl statically. The map keeps it open.

## Codec SPI (internal)

The serializer never reflects on `INodeSource` or `IDependencySource` directly —
it defers per‑impl encoding to codecs registered in an internal `CodecRegistry`.
The SPI is not part of the public API in v1.

The codec's `read` return type is the SPI base (`INodeSource` /
`IDependencySource`), not the type parameter `S`. This lets a codec write one
impl and read back a plain copy of a different impl — the graphdb codecs use
this to round-trip `GraphDb*Source` → `Default*Source` without a Bolt client
at read time.

```kotlin
internal interface NodeSourceCodec<S : INodeSource> {
    val typeId: String                              // written to SourceRef.type
    val sourceClass: Class<S>
    fun write(source: S): Map<String, String>
    fun read(identifier: String, payload: Map<String, String>): INodeSource   // not S
}

internal interface DepSourceCodec<S : IDependencySource> { /* mirror */ }

internal class CodecRegistry {
    fun register(codec: NodeSourceCodec<*>): CodecRegistry
    fun register(codec: DepSourceCodec<*>): CodecRegistry

    fun nodeCodecFor(source: INodeSource): NodeSourceCodec<INodeSource>
    fun nodeCodecFor(typeId: String):      NodeSourceCodec<INodeSource>
    fun depCodecFor(source: IDependencySource): DepSourceCodec<IDependencySource>
    fun depCodecFor(typeId: String):            DepSourceCodec<IDependencySource>

    companion object {
        /** Registry preloaded with codecs for DefaultNodeSource / DefaultDependencySource. */
        fun defaults(): CodecRegistry
    }
}
```

Lookup falls back from exact class match to walking the source's superclasses,
so codecs registered for `DefaultNodeSource` also handle simple subclasses
unless overridden by an exact-class codec.

### Codecs shipped out of the box

`HGGraphJson` uses `CodecRegistry.defaults()`, which preloads:

| typeId          | Writes                                                            | Reads back into                                       |
|-----------------|-------------------------------------------------------------------|-------------------------------------------------------|
| `default-node`  | `DefaultNodeSource.properties` plus `_idType`                     | `DefaultNodeSource(id, props)`                        |
| `default-dep`   | `DefaultDependencySource.properties` plus `_idType`               | `DefaultDependencySource(id, props)`                  |
| `graphdb-root`  | identifier + `_idType` only (no Bolt I/O, no `labels`/`properties`) | `DefaultNodeSource(id)`                              |
| `graphdb-node`  | identifier + `_idType` only (no Bolt I/O, no `labels`/`properties`) | `DefaultNodeSource(id)`                              |
| `graphdb-dep`   | identifier + `_idType` only (no Bolt I/O, no `properties`, drops `type`) | `DefaultDependencySource(id)`                   |

The `_idType` payload key (`long` / `int` / `string`) lets the reader coerce
the wire string back to the original identifier type. Adding new identifier
types is a codec-only change.

The three `graphdb-*` codecs are deliberately identifier-only in v1: the
lazy `labels` / `properties` on `GraphDbNodeSource` are NOT force-loaded
from Neo4j on write, and `GraphDbDependencySource.type` is dropped
(`HGCoreDependency.type` already carries that field on the `DepRecord`).
The read side has no Bolt client, so all graphdb sources hydrate as plain
`Default*Source` copies.

To preserve `labels` / `properties` in a future revision, extend the graphdb
codecs to materialize them on write and bump `GraphSnapshot.SCHEMA_VERSION`.

## Kind round-trip

`HGNode.kind` is `Any?`. The serializer writes `KindRef(type, value)` where
`type` is the kind class's FQCN and `value` is its string form:

- **`null` kind** → `KindRef` is null in the wire record.
- **Enum kind** (e.g. `JavaNodeKind`) → `value = enum.name`. Reader resolves
  via `Class.forName(type).enumConstants.first { (it as Enum<*>).name == value }`.
- **String kind** → `type = "java.lang.String"`, `value = kindString`.

No codec registration is needed for these cases. Other kind classes raise
`UnsupportedOperationException` on write.

## Writer (internal)

One pre-order traversal of the tree, one identity-dedup pass for dependencies:

```kotlin
internal class GraphWriter(private val codecs: CodecRegistry) {
    fun write(root: HGRootNode): GraphSnapshot
}
```

Invariants:

- **Traverse only `outgoingCoreDependencies`.** Every dependency appears in
  exactly one `outgoing` list and one `incoming` list (by `HierarchicalGraphFactory`'s
  construction); picking the outgoing side avoids duplicates without
  identity-equality machinery on the wire.
- **Never touch derived caches.** `accumulated*`, `predecessors`,
  `getOutgoingDependenciesTo(...)` etc. are derived; touching them on the write
  path would force pointless work and risk emitting nothing new.
- **`GraphDbNodeSource` materialization is the caller's responsibility.** If
  a future graphdb codec is registered, it must trigger `source.labels` /
  `source.properties` before reading them. This module ships no graphdb codec,
  so this is just a warning for downstream codec authors.

## Reader (internal)

Two passes: create nodes (parents before children), then attach dependencies.

```kotlin
internal class GraphReader(private val codecs: CodecRegistry) {
    fun read(snapshot: GraphSnapshot): HGRootNode
}
```

Invariants:

- **Parents before children.** `HierarchicalGraphFactory.createNode(root, parent, ...)`
  requires the parent to exist. The writer's pre-order traversal satisfies this
  with one linear pass on the reader side.
- **Caches stay cold.** The factory never populates the derived caches, so the
  deserialized graph is in a clean state — first access to `accumulated*` etc.
  rebuilds lazily.
- **`weight` and `attributesBitmap` are `var`** on `HGCoreDependency` —
  settable post-construction; that's why they're not part of
  `createCoreDependency`'s signature.

## Jackson configuration

`HGGraphJson` holds two long-lived `ObjectMapper` singletons (compact + pretty),
both built from `jacksonObjectMapper()` (the Kotlin module) with:

- `SerializationFeature.INDENT_OUTPUT` toggled per mapper.
- `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` disabled — leaves
  forward-compat room for schema fields added by later writers; combine with
  the explicit `schemaVersion` check for hard breaks.

`GraphReader.read` enforces `schemaVersion == GraphSnapshot.SCHEMA_VERSION`
and throws `IllegalArgumentException` otherwise.

## Module layout

```
io.hierograph.hierarchicalgraph.serialization/
├── HGGraphJson.kt              ← public entry point
└── internal/
    ├── CodecRegistry.kt
    ├── DefaultCodecs.kt        (DefaultNodeSourceCodec, DefaultDependencySourceCodec; plus shared identifier helpers ID_TYPE_KEY, identifierTypeKey(), coerceIdentifier())
    ├── GraphDbCodecs.kt        (GraphDbRootNodeSourceCodec, GraphDbNodeSourceCodec, GraphDbDependencySourceCodec)
    ├── GraphReader.kt
    ├── GraphSnapshot.kt        (GraphSnapshot, NodeRecord, DepRecord, KindRef, SourceRef)
    ├── GraphWriter.kt
    ├── KindReferences.kt       (encodeKind / decodeKind)
    └── SourceCodecs.kt         (NodeSourceCodec, DepSourceCodec)
```

The `.internal` subpackage is a convention — kept off the public surface so it
can evolve freely. Promote types out of it only when there's a concrete
external consumer.

## What this does NOT preserve

| Thing                                                | Behavior                                              |
|------------------------------------------------------|-------------------------------------------------------|
| Identity equality across round-trip                  | Broken; `node1 === node2` won't hold. Equality on `identifier` does. |
| `HGRootNode` extensions (`registerExtension(...)`)   | Skipped. Runtime hooks (Spring beans, bolt clients) are caller-owned. |
| `HGAggregatedDependency`                             | Not serialized; reader regenerates on demand from core deps. |
| `GraphDbDependencySource.userObject`                 | Not serialized; runtime-only annotation.              |
| `INodeSource.node` / `IDependencySource.dependency`  | Set automatically by `HierarchicalGraphFactory` on read. |

## Approximate cost

- **Compute**: O(N + E) on both write and read.
- **Wire size** (gzipped JSON, rough): ~50–80 bytes per node, ~30–50 bytes per
  core dep. A 100k-node / 500k-edge graph lands ~30–60 MB uncompressed,
  ~5–15 MB gzipped.
