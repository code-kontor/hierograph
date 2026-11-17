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
package io.hierograph.gradle.plugin

import io.hierograph.manifest.BuildTool
import io.hierograph.manifest.ClasspathEntry
import io.hierograph.manifest.MANIFEST_DIR
import io.hierograph.manifest.MergeRequest
import io.hierograph.manifest.ModuleDescriptor
import io.hierograph.manifest.ModuleSource
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.invariantSeparatorsPathString

/** The task's properties, read as Gradle properties (`-P`) or system properties (`-D`); the names equal the Maven ones. */
internal data class Options(val manifestDir: File, val manifestDirGiven: Boolean, val skip: Boolean) {
    companion object {
        fun read(settings: Settings): Options {
            fun property(name: String): String? =
                settings.providers.gradleProperty(name).orElse(settings.providers.systemProperty(name)).orNull
            val dir = property("hierograph.manifestDir")
            return Options(
                manifestDir = (if (dir != null) settings.rootDir.resolve(dir) else File(settings.rootDir, MANIFEST_DIR)).normalize(),
                manifestDirGiven = dir != null,
                skip = property("hierograph.skip")?.let { it.isEmpty() || it.toBoolean() } ?: false,
            )
        }
    }
}

/**
 * One project with the `java` plugin, as collected at configuration time: what can be read without resolving, and
 * the four classpath configurations, resolved only when the project's [HierographClasspathsTask] runs.
 */
internal class ModulePlan(
    val path: String,
    val gav: String,
    val name: String,
    val mainOutputDirs: List<String>,
    val testOutputDirs: List<String>,
    val sourceRoots: List<String>,
    val testSourceRoots: List<String>,
    val compile: ResolvableDependencies,
    val runtime: ResolvableDependencies,
    val testCompile: ResolvableDependencies,
    val testRuntime: ResolvableDependencies,
)

/** The resolved classpaths of one module, in manifest order. */
internal class ModuleClasspaths(val classpath: List<ClasspathEntry>, val testClasspath: List<ClasspathEntry>)

/**
 * Everything the task needs, collected once all projects are evaluated. [projectGavs] maps the build-tree path of
 * every project of this build to its GAV, so a project dependency becomes an internal entry. [skipped] are the
 * projects without a module, with the reason. [classpaths] is filled by the projects' [HierographClasspathsTask]s,
 * by project path, possibly in parallel.
 */
internal class ManifestPlan(
    val rootDir: File,
    val gradleVersion: String,
    val initScripts: List<File>,
    val options: Options,
    val modules: List<ModulePlan>,
    val projectGavs: Map<String, String>,
    val skipped: List<Pair<String, String>>,
    val buildFiles: List<Path>,
    val buildFilesReadAt: Instant,
) {
    val classpaths = ConcurrentHashMap<String, ModuleClasspaths>()
}

/**
 * The Gradle side of a manifest merge: what only Gradle knows, handed to the build-tool-independent
 * `ManifestWriter` — the counterpart of the Maven plugin's `MavenManifest`.
 *
 * - **Modules** are the projects with the `java` plugin; their `main` and `test` source sets (§4 of the spec).
 * - **Classpaths** are the compile configuration's entries in classpath order, followed by the entries only in the
 *   runtime configuration; a component keeps its first position.
 * - **Build files** are the files whose change can change the manifest (§6), inside the build root.
 * - **Regeneration command** reruns the task, with the invocation's init scripts and manifest directory.
 */
internal object GradleManifest {

    private const val KOTLIN_MULTIPLATFORM = "org.jetbrains.kotlin.multiplatform"

    /** Directories never walked for build files: build output and tool state. */
    private val SKIPPED_DIRS = setOf(".gradle", ".kotlin", ".git", ".idea", MANIFEST_DIR)

    private val SCRIPT_SUFFIXES = listOf(".gradle", ".gradle.kts")

    val pluginVersion: String by lazy {
        val properties = Properties()
        GradleManifest::class.java.getResourceAsStream("plugin.properties")?.use(properties::load)
        properties.getProperty("version", "unknown")
    }

    fun plan(settings: Settings, gradle: Gradle, options: Options, buildFilesReadAt: Instant): ManifestPlan {
        val root = gradle.rootProject
        val projectGavs = root.allprojects.associate { it.buildTreePath to gav(it) }
        val modules = ArrayList<ModulePlan>()
        val skipped = ArrayList<Pair<String, String>>()
        for (project in root.allprojects) {
            when {
                project.plugins.hasPlugin(KOTLIN_MULTIPLATFORM) -> skipped += project.path to "Kotlin Multiplatform is not supported"
                !project.plugins.hasPlugin("java") -> skipped += project.path to "no java plugin"
                else -> modules += module(project)
            }
        }
        return ManifestPlan(
            rootDir = settings.rootDir.absoluteFile,
            gradleVersion = gradle.gradleVersion,
            initScripts = gradle.startParameter.initScripts.map { it.absoluteFile },
            options = options,
            modules = modules,
            projectGavs = projectGavs,
            skipped = skipped,
            buildFiles = buildFiles(settings, gradle),
            buildFilesReadAt = buildFilesReadAt,
        )
    }

    fun mergeRequest(plan: ManifestPlan): MergeRequest = MergeRequest(
        manifestDir = plan.options.manifestDir.toPath(),
        generator = "io.hierograph.gradle.plugin/$pluginVersion",
        reactorRoot = plan.rootDir.path,
        buildTool = BuildTool("gradle", plan.gradleVersion),
        regenerate = regenerateCommand(plan),
        modules = plan.modules.map { ModuleSource(it.gav, it.name, descriptor = describe(plan, it)) },
        buildFiles = plan.buildFiles,
        buildFilesReadAt = plan.buildFilesReadAt,
    )

    /**
     * `group:name:version` of [project]. An empty group (Gradle's default for the root project) falls back to the
     * root project's name; a version of `unspecified` is kept.
     */
    fun gav(project: Project): String {
        val group = project.group.toString().ifEmpty { project.rootProject.name }
        return "$group:${project.name}:${project.version}"
    }

    private fun module(project: Project): ModulePlan {
        val sourceSets = project.extensions.getByType(JavaPluginExtension::class.java).sourceSets
        val main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME)
        fun incoming(name: String) = project.configurations.getByName(name).incoming
        return ModulePlan(
            path = project.path,
            gav = gav(project),
            name = project.name,
            mainOutputDirs = main.output.classesDirs.files.map { it.absolutePath },
            testOutputDirs = test.output.classesDirs.files.map { it.absolutePath },
            sourceRoots = sourceRoots(main),
            testSourceRoots = sourceRoots(test),
            compile = incoming(main.compileClasspathConfigurationName),
            runtime = incoming(main.runtimeClasspathConfigurationName),
            testCompile = incoming(test.compileClasspathConfigurationName),
            testRuntime = incoming(test.runtimeClasspathConfigurationName),
        )
    }

    /** All source directories of [sourceSet] (Java, Kotlin, Groovy, …) except the resources, like Maven's compile source roots. */
    private fun sourceRoots(sourceSet: SourceSet): List<String> {
        val resources = sourceSet.resources.srcDirs
        return sourceSet.allSource.srcDirs.filter { it !in resources }.map { it.absolutePath }
    }

    private fun describe(plan: ManifestPlan, module: ModulePlan): ModuleDescriptor {
        val classpaths = checkNotNull(plan.classpaths[module.path]) { "${module.path}:${HierographClasspathsTask.NAME} did not run" }
        return ModuleDescriptor(
            gav = module.gav,
            name = module.name,
            mainOutputDirs = module.mainOutputDirs,
            testOutputDirs = module.testOutputDirs,
            sourceRoots = module.sourceRoots,
            testSourceRoots = module.testSourceRoots,
            classpath = classpaths.classpath,
            testClasspath = classpaths.testClasspath,
        )
    }

    /** Resolves the classpaths of [module]; runs in the module's own project (its [HierographClasspathsTask]). */
    fun classpaths(plan: ManifestPlan, module: ModulePlan) = ModuleClasspaths(
        classpath = classpath(plan, module, module.compile, module.runtime),
        testClasspath = classpath(plan, module, module.testCompile, module.testRuntime),
    )

    /** The entries of [compile] in classpath order, followed by those only in [runtime]; first occurrence wins. */
    private fun classpath(plan: ManifestPlan, module: ModulePlan, compile: ResolvableDependencies, runtime: ResolvableDependencies): List<ClasspathEntry> {
        val entries = LinkedHashMap<String, ClasspathEntry>()
        for (configuration in listOf(compile, runtime)) {
            for (entry in entries(plan, module, configuration)) entries.putIfAbsent(entry.gav, entry)
        }
        return entries.values.toList()
    }

    /**
     * The classpath entries of one configuration, in the order of its resolved artifacts — the order Gradle passes
     * to the compiler. Listing a project component's artifact yields only its location; nothing is built.
     */
    private fun entries(plan: ManifestPlan, module: ModulePlan, configuration: ResolvableDependencies): List<ClasspathEntry> {
        val artifacts = try {
            configuration.artifacts.artifacts
        } catch (e: Exception) {
            // The manifest would be wrong without the dependency, as in the Maven goal.
            throw GradleException("${HierographManifestTask.NAME}: cannot resolve the dependencies of ${module.path} (${configuration.name}): ${e.message}", e)
        }
        val versions: Map<ComponentIdentifier, String> by lazy {
            configuration.resolutionResult.allComponents.associate { component ->
                component.id to component.moduleVersion.let { "${it?.group}:${it?.name}:${it?.version}" }
            }
        }
        return artifacts.map { artifact ->
            val file = artifact.file.absoluteFile
            when (val id = artifact.id.componentIdentifier) {
                is ProjectComponentIdentifier -> plan.projectGavs[id.buildTreePath]
                    ?.let { ClasspathEntry(gav = it, internal = true, jar = null) }
                    // A project of another (included) build: by coordinates, not scanned.
                    ?: ClasspathEntry(gav = versions[id] ?: id.buildTreePath, internal = false, jar = null)
                is ModuleComponentIdentifier -> ClasspathEntry("${id.group}:${id.module}:${id.version}", internal = false, jar = file.path)
                // A file dependency (`files(…)`, `fileTree(…)`): named by its path relative to the build root.
                else -> ClasspathEntry(
                    gav = "file:" + plan.rootDir.toPath().relativize(file.toPath()).invariantSeparatorsPathString,
                    internal = false,
                    jar = file.path,
                )
            }
        }
    }

    /**
     * `./gradlew -q [--init-script <abs>]… [-Phierograph.manifestDir=<abs>] hierographManifest`: the wrapper when the
     * root directory holds one (`gradlew.bat` on Windows), otherwise `gradle` from the `PATH`.
     */
    private fun regenerateCommand(plan: ManifestPlan): List<String> {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val wrapper = if (windows) "gradlew.bat" else "gradlew"
        val gradle = if (File(plan.rootDir, wrapper).isFile) "./$wrapper" else if (windows) "gradle.bat" else "gradle"
        return buildList {
            add(gradle)
            add("-q")
            plan.initScripts.forEach { add("--init-script"); add(it.path) }
            if (plan.options.manifestDirGiven) add("-Phierograph.manifestDir=${plan.options.manifestDir.path}")
            add(HierographManifestTask.NAME)
        }
    }

    /**
     * The files that define the build, inside the build root: the settings script, every project's build script and
     * `gradle.properties`, the scripts next to them and under `gradle/` (applied scripts, version catalogs), the
     * wrapper properties, and every file of `buildSrc` and of the included builds except their build output.
     */
    private fun buildFiles(settings: Settings, gradle: Gradle): List<Path> {
        val root = settings.rootDir.toPath().toAbsolutePath().normalize()
        val files = ArrayList<Path>()
        fun add(file: File?) {
            val path = file?.toPath()?.toAbsolutePath()?.normalize() ?: return
            if (path.startsWith(root) && Files.isRegularFile(path)) files.add(path)
        }
        fun addScripts(dir: File) = dir.listFiles()?.filter { f -> SCRIPT_SUFFIXES.any { f.name.endsWith(it) } }?.forEach(::add)

        add(settings.buildscript.sourceFile)
        addScripts(settings.settingsDir)
        add(File(settings.rootDir, "gradle.properties"))
        add(File(settings.rootDir, "gradle/wrapper/gradle-wrapper.properties"))
        File(settings.rootDir, "gradle").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { f -> f.isFile && (f.name.endsWith(".toml") || SCRIPT_SUFFIXES.any { f.name.endsWith(it) }) }
            ?.forEach(::add)
        for (project in gradle.rootProject.allprojects) {
            add(project.buildFile)
            add(File(project.projectDir, "gradle.properties"))
            addScripts(project.projectDir)
        }
        val buildLogic = listOf(File(settings.rootDir, "buildSrc")) + gradle.includedBuilds.map { it.projectDir }
        buildLogic.filter { it.isDirectory }.forEach { dir -> walkBuild(dir).forEach(::add) }
        return files.distinct()
    }

    /**
     * Every file of the build in [dir], skipping tool state and each project's `build/` directory — a `build`
     * directory next to a build or settings script, or at the top of [dir] (a `buildSrc` may have no script).
     */
    private fun walkBuild(dir: File): Sequence<File> = dir.walkTopDown()
        .onEnter { d ->
            d.name !in SKIPPED_DIRS && !(d.name == "build" && (d.parentFile == dir || isProjectDir(d.parentFile)))
        }
        .filter { it.isFile }

    private fun isProjectDir(dir: File): Boolean =
        listOf("build", "settings").any { name -> SCRIPT_SUFFIXES.any { File(dir, name + it).isFile } }
}
