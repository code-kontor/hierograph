/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.server.graphservice.views

import io.hierograph.server.graphservice.GraphSnapshot
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.NodeSearchIndex
import io.hierograph.server.graphservice.pagination.DataHash
import io.hierograph.server.views.MaterializedView
import io.hierograph.server.views.ViewDocumentException
import io.hierograph.server.views.ViewMaterializer
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.measureTimedValue

/** A view applied to one graph snapshot: the registry [entry], the [materialized] view and its [snapshot]. */
class ViewSnapshot(
    val entry: ViewEntry,
    val materialized: MaterializedView,
    /** The base snapshot with the view's model, search index and data hash; [GraphSnapshot.view] is the view's name. */
    val snapshot: GraphSnapshot,
    /** The base snapshot the view was materialized from. */
    val base: GraphSnapshot,
)

/** A view document that is defined but cannot be applied because it does not parse. */
class InvalidViewException(val entry: ViewEntry) : IllegalStateException(
    "View '${entry.name}' (${entry.file}) is invalid: ${entry.problems.joinToString("; ")}",
)

/**
 * The views of the server (views specification, §7): their documents ([ViewRegistry]) and their
 * materialization against the graph.
 *
 * A view is materialized lazily, on its first request against a graph snapshot, and cached with that
 * snapshot until the graph or the document changes (§7.3). The first request builds it under a lock per
 * view, so concurrent first requests build it once. A materialized view is never changed afterwards.
 *
 * Directories: `hierograph.views.projectDir` (default `<build root>/.hierograph/views`) and
 * `hierograph.views.stateDir` (default `<state dir>/runtime-views`).
 */
@Service
class ViewService @Autowired constructor(private val graphService: HierarchicalGraphService) {

    companion object {
        private val log = LoggerFactory.getLogger(ViewService::class.java)
    }

    @Value("\${hierograph.views.projectDir:}")
    private var projectDirSetting: String = ""

    @Value("\${hierograph.views.stateDir:}")
    private var stateDirSetting: String = ""

    private var registryOverride: ViewRegistry? = null

    /** For construction without Spring, e.g. in tests: the views of [registry] over [graphService]'s graph. */
    constructor(graphService: HierarchicalGraphService, registry: ViewRegistry) : this(graphService) {
        registryOverride = registry
        init()
    }

    val registry: ViewRegistry by lazy {
        registryOverride ?: ViewRegistry(projectDir(), stateDir()).also {
            log.info("Views: project documents in {}, state documents in {}", projectDir(), stateDir())
        }
    }

    private fun projectDir(): Path =
        setting(projectDirSetting) ?: graphService.buildRootDirectory.resolve(".hierograph").resolve("views")

    private fun stateDir(): Path =
        setting(stateDirSetting) ?: graphService.stateDirectory.resolve("runtime-views")

    private fun setting(value: String): Path? =
        value.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }

    private class Cached(val text: String, val view: ViewSnapshot)

    /** The latest materialization of each view; only ever one per view, for the snapshot it was built from. */
    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val materializations = AtomicLong()

    /** How many times a view has been materialized; for tests and diagnostics. */
    val materializationCount: Long get() = materializations.get()

    /** How many materialized views are cached; for tests and diagnostics. */
    internal val cachedViewCount: Int get() = cache.size

    /** Runs after a view of the live snapshot is materialized, before it is cached; for tests of that window. */
    internal var beforeCacheWrite: (() -> Unit)? = null

    @PostConstruct
    fun init() {
        // A view of a replaced snapshot would keep the whole old graph alive; drop it with the snapshot.
        graphService.onSnapshotPublished { live -> cache.values.removeIf { it.view.base !== live } }
    }

    /** The applicable views, valid or not, and the invalid duplicates; see [ViewRegistry.entries]. */
    fun entries(): List<ViewEntry> = registry.entries()

    /** The applicable view named [name]; fails with [UnknownViewException]. */
    fun entry(name: String): ViewEntry = registry.find(name) ?: throw UnknownViewException(name, registry.names())

    /**
     * The view named [name] applied to the snapshot this thread reads ([HierarchicalGraphService.currentSnapshot]):
     * the pinned one inside a tool call, else the live one; inside [withView], the base of the view in use.
     * Fails with [UnknownViewException] for an unknown
     * name, [InvalidViewException] for a document that does not parse, and [IllegalStateException] without a
     * loaded graph.
     */
    fun view(name: String): ViewSnapshot = view(entry(name), base())

    /**
     * The snapshot views are materialized from on this thread: inside [withView] the base of the view in
     * use, so asking for a view inside a view never stacks one view on another; else the current snapshot.
     */
    private fun base(): GraphSnapshot {
        currentView()?.let { return it.base }
        val snapshot = graphService.currentSnapshot() ?: error("Hierarchical graph has not been loaded yet")
        check(snapshot.view == null) { "This thread reads view '${snapshot.view}' outside withView; views do not stack" }
        return snapshot
    }

    private val current = ThreadLocal<ViewSnapshot?>()

    /** The view [withView] runs this thread in, or `null` outside one. */
    fun currentView(): ViewSnapshot? = current.get()

    /**
     * Runs [block] in [view]: its snapshot is pinned to the current thread ([HierarchicalGraphService.withPinnedSnapshot]),
     * so every read of the graph sees the view's hierarchy, and [currentView] returns it. Fails when the
     * thread is already pinned to another snapshot, because the pin would then not take effect.
     */
    fun <T> withView(view: ViewSnapshot, block: () -> T): T =
        graphService.withPinnedSnapshot(view.snapshot) {
            check(graphService.currentSnapshot() === view.snapshot) {
                "Cannot apply view '${view.entry.name}': this thread is already pinned to another snapshot"
            }
            val previous = current.get()
            current.set(view)
            try {
                block()
            } finally {
                current.set(previous)
            }
        }

    /**
     * Stores [json] as a state view and applies it to the current snapshot; returns the applied view. Fails
     * with [ViewDocumentException] when the document is invalid.
     */
    fun define(json: String): ViewSnapshot {
        val entry = registry.define(json)
        cache.remove(entry.name)
        return view(entry, base())
    }

    /** Deletes the state view [name]; returns the project view that applies now, or `null`. See [ViewRegistry.delete]. */
    fun delete(name: String): ViewEntry? {
        val restored = registry.delete(name)
        cache.remove(name)
        return restored
    }

    private fun view(entry: ViewEntry, base: GraphSnapshot): ViewSnapshot {
        if (!entry.isValid) throw InvalidViewException(entry)
        cached(entry, base)?.let { return it }
        synchronized(locks.computeIfAbsent(entry.name) { Any() }) {
            cached(entry, base)?.let { return it }
            val view = materialize(entry, base)
            // Only views of the live snapshot are cached: a call still pinned to a replaced snapshot gets its
            // view built for it, without evicting the live one.
            if (base === graphService.liveSnapshot()) {
                beforeCacheWrite?.invoke()
                val cached = Cached(entry.text, view)
                cache[entry.name] = cached
                // A publication between the check and the write ran its eviction too early to see this entry;
                // remove it here, or it would keep the replaced graph alive until the view's next request.
                if (base !== graphService.liveSnapshot()) cache.remove(entry.name, cached)
            }
            return view
        }
    }

    private fun cached(entry: ViewEntry, base: GraphSnapshot): ViewSnapshot? =
        cache[entry.name]?.takeIf { it.view.base === base && it.text == entry.text }?.view

    private fun materialize(entry: ViewEntry, base: GraphSnapshot): ViewSnapshot {
        val (materialized, elapsed) = measureTimedValue { ViewMaterializer.materialize(base.model, entry.document!!) }
        materializations.incrementAndGet()
        val report = materialized.report
        log.info(
            "Materialized view '{}' for graph generation {} in {}: {} groups, {} errors, {} warnings",
            entry.name, base.generation, elapsed, materialized.groupNodes.size, report.errors.size, report.warnings.size,
        )
        val snapshot = base.copy(
            model = materialized.model,
            // A grouping view only adds group nodes; a restructuring view also adds virtual nodes and removes
            // real ones, so its index is built from its own hierarchy.
            searchIndex = if (materialized.document.restructure && materialized.model !== base.model) {
                NodeSearchIndex.build(materialized.model)
            } else {
                base.searchIndex.withAdditional(materialized.groupNodes.values)
            },
            dataHash = DataHash.combine(base.dataHash, entry.text),
            view = entry.name,
        )
        return ViewSnapshot(entry, materialized, snapshot, base)
    }
}
