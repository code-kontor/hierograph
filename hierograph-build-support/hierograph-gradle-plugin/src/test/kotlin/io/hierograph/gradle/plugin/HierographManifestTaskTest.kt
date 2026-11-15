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

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/** The `hierographManifest` task, through TestKit, on every Gradle version under test. */
class HierographManifestTaskTest {

    companion object {
        @JvmStatic
        fun versions() = gradleVersions()

        private const val SLF4J = "org.slf4j:slf4j-api:2.0.16"
        private const val SLF4J_SIMPLE = "org.slf4j:slf4j-simple:2.0.16"
        private const val LANG3 = "org.apache.commons:commons-lang3:3.17.0"
        private const val ANNOTATION = "jakarta.annotation:jakarta.annotation-api:2.1.1"
        private const val OPENTEST4J = "org.opentest4j:opentest4j:1.3.0"
    }

    @TempDir
    lateinit var tempDir: File

    /** [tempDir] canonical, as Gradle reports paths (`/private/var` rather than `/var` on macOS). */
    private val tmp: File get() = tempDir.canonicalFile

    private fun build(version: String) = TestBuild(File(tmp, "build").apply { mkdirs() }, version, File(tmp, "project-cache"))

    /** Root project without `java`, a `java-library` and an application depending on it; Kotlin DSL. */
    private fun multiProject(version: String) = build(version).apply {
        file(
            "settings.gradle.kts",
            """
            plugins { id("io.hierograph") }
            rootProject.name = "demo"
            include("lib", "app")
            """,
        )
        file(
            "build.gradle.kts",
            """
            allprojects {
                group = "com.example"
                version = "1.0"
                repositories { mavenCentral() }
            }
            """,
        )
        file(
            "lib/build.gradle.kts",
            """
            plugins { `java-library` }
            dependencies {
                api("$SLF4J")
                implementation("$LANG3")
                compileOnly("$ANNOTATION")
                runtimeOnly("$SLF4J_SIMPLE")
                testImplementation("$OPENTEST4J")
            }
            // Gradle's own classpath order, to compare the manifest with.
            tasks.register("printClasspaths") {
                val classpaths = listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
                    .associateWith { configurations.getByName(it) }
                doLast { classpaths.forEach { (name, files) -> println("classpath " + name + "=" + files.joinToString(",") { it.name }) } }
            }
            """,
        )
        file(
            "app/build.gradle.kts",
            """
            plugins { java }
            dependencies { implementation(project(":lib")) }
            """,
        )
        file("lib/src/main/java/com/example/lib/Lib.java", "package com.example.lib; public class Lib {}")
        file("lib/src/main/resources/lib.properties", "a=b")
        file("app/src/main/java/com/example/app/App.java", "package com.example.app; public class App { com.example.lib.Lib lib; }")
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `writes one module per java project with classpaths in classpath order`(version: String) {
        val build = multiProject(version)
        val before = build.files()

        val result = build.run("hierographManifest", "--warning-mode=fail")

        // Nothing is built: only the plugin's tasks run, and only the manifest directory is written.
        assertThat(result.tasks.map { it.path })
            .containsExactlyInAnyOrder(":app:hierographClasspaths", ":lib:hierographClasspaths", ":hierographManifest")
        assertThat(result.task(":hierographManifest")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(build.files() - before.toSet()).containsExactlyInAnyOrder(".hierograph/manifest.json", ".hierograph/manifest.lock")
        assertThat(result.output).contains("hierographManifest: 2 module(s) → ")

        val manifest = build.manifest()
        assertThat(manifest.str("reactorRoot")).isEqualTo(build.dir.absolutePath)
        assertThat(manifest.getAsJsonObject("buildTool").str("name")).isEqualTo("gradle")
        assertThat(manifest.getAsJsonObject("buildTool").str("version")).isEqualTo(version)
        assertThat(manifest.str("generator")).startsWith("io.hierograph.gradle.plugin/")
        assertThat(manifest.modules().map { it.str("gav") }).containsExactly("com.example:app:1.0", "com.example:lib:1.0")

        val lib = manifest.module("lib")
        val libDir = File(build.dir, "lib").absolutePath
        assertThat(lib.strings("mainOutputDirs")).containsExactly("$libDir/build/classes/java/main")
        assertThat(lib.strings("testOutputDirs")).containsExactly("$libDir/build/classes/java/test")
        assertThat(lib.strings("sourceRoots")).containsExactly("$libDir/src/main/java") // no resources
        assertThat(lib.strings("testSourceRoots")).containsExactly("$libDir/src/test/java")
        // compileClasspath, then the runtime-only entries; compileOnly is not on the test classpath.
        assertThat(lib.classpath("classpath")).containsExactly(SLF4J, ANNOTATION, LANG3, SLF4J_SIMPLE)
        assertThat(lib.classpath("testClasspath")).containsExactly(SLF4J, LANG3, OPENTEST4J, SLF4J_SIMPLE)
        val slf4j = lib.getAsJsonArray("classpath").first().asJsonObject
        assertThat(File(slf4j.str("jar")!!)).isFile().hasName("slf4j-api-2.0.16.jar")
        // The order is the one Gradle passes to the compiler: each compile configuration's, then the runtime additions.
        val gradleOrder = Regex("classpath (\\w+)=(.*)").findAll(build.run(":lib:printClasspaths", "-q").output)
            .associate { it.groupValues[1] to it.groupValues[2].split(',') }
        fun jars(key: String) = lib.getAsJsonArray(key).map { File(it.asJsonObject.str("jar")!!).name }
        assertThat(jars("classpath")).isEqualTo((gradleOrder.getValue("compileClasspath") + gradleOrder.getValue("runtimeClasspath")).distinct())
        assertThat(jars("testClasspath")).isEqualTo((gradleOrder.getValue("testCompileClasspath") + gradleOrder.getValue("testRuntimeClasspath")).distinct())

        // A project dependency is internal, by coordinates, with no jar.
        val app = manifest.module("app")
        assertThat(app.classpath("classpath")).containsExactly("*com.example:lib:1.0", SLF4J, SLF4J_SIMPLE, LANG3)
        val internal = app.getAsJsonArray("classpath").first().asJsonObject
        assertThat(internal.get("jar").isJsonNull).isTrue()
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `a parallel build resolves each project's classpaths in that project`(version: String) {
        val build = multiProject(version)
        build.run("hierographManifest")
        val serial = build.manifest()
        File(build.dir, ".hierograph").deleteRecursively()

        // Resolving another project's configuration needs that project's lock, which a parallel build enforces.
        build.file("gradle.properties", "org.gradle.parallel=true")
        build.run("hierographManifest", "--warning-mode=fail")

        assertThat(build.manifest().get("modules")).isEqualTo(serial.get("modules"))
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `rewrites the manifest only when the build changes`(version: String) {
        val build = multiProject(version)
        build.run("hierographManifest")
        val first = File(build.dir, ".hierograph/manifest.json").readText()

        val unchanged = build.run("hierographManifest")
        assertThat(unchanged.output).contains("(unchanged)")
        assertThat(File(build.dir, ".hierograph/manifest.json").readText()).isEqualTo(first)

        File(build.dir, "app/build.gradle.kts").appendText("dependencies { implementation(\"$OPENTEST4J\") }\n")
        val changed = build.run("hierographManifest")
        assertThat(changed.output).doesNotContain("(unchanged)")
        assertThat(build.manifest().module("app").classpath("classpath")).contains(OPENTEST4J)
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `kotlin and java output of a source set form one module`(version: String) {
        val build = build(version).apply {
            file(
                "settings.gradle.kts",
                """
                pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
                plugins { id("io.hierograph") }
                rootProject.name = "kt"
                """,
            )
            file(
                "build.gradle.kts",
                """
                plugins { kotlin("jvm") version "2.2.21" }
                group = "com.example"
                version = "1.0"
                repositories { mavenCentral() }
                """,
            )
            file("src/main/kotlin/K.kt", "class K")
            file("src/main/java/J.java", "public class J {}")
        }

        val result = build.run("hierographManifest", "-Pkotlin.project.persistent.dir=${File(tmp, "kotlin").path}")

        assertThat(result.tasks.map { it.path }).containsExactly(":hierographClasspaths", ":hierographManifest")
        val module = build.manifest().module("kt")
        val dir = build.dir.absolutePath
        assertThat(module.strings("mainOutputDirs")).containsExactlyInAnyOrder("$dir/build/classes/java/main", "$dir/build/classes/kotlin/main")
        assertThat(module.strings("sourceRoots")).containsExactlyInAnyOrder("$dir/src/main/java", "$dir/src/main/kotlin")
        assertThat(module.classpath("classpath")).anyMatch { it.startsWith("org.jetbrains.kotlin:kotlin-stdlib:") }
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `lists the build files, including buildSrc without its output`(version: String) {
        val build = build(version).apply {
            file(
                "settings.gradle",
                """
                plugins { id 'io.hierograph' }
                rootProject.name = 'groovy'
                include 'a'
                """,
            )
            file("gradle.properties", "org.gradle.jvmargs=-Xmx512m")
            file("gradle/libs.versions.toml", "[libraries]\nlang3 = \"$LANG3\"")
            file("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://example.invalid/gradle.zip")
            file("gradle/conventions.gradle", "ext.applied = true")
            file("build.gradle", "subprojects { repositories { mavenCentral() } }")
            file(
                "a/build.gradle",
                """
                plugins { id 'java'; id 'demo.convention' }
                apply from: rootProject.file('gradle/conventions.gradle')
                dependencies { implementation libs.lang3 }
                """,
            )
            file("buildSrc/build.gradle", "plugins { id 'groovy-gradle-plugin' }")
            file("buildSrc/src/main/groovy/demo.convention.gradle", "// convention")
        }

        val result = build.run("hierographManifest")

        // buildSrc is built before configuration; the build's own projects are not.
        assertThat(result.tasks.map { it.path }.filterNot { it.startsWith(":buildSrc:") }).containsExactly(":a:hierographClasspaths", ":hierographManifest")
        val manifest = build.manifest()
        assertThat(manifest.module("a").classpath("classpath")).containsExactly(LANG3)
        assertThat(manifest.buildFiles(build.dir)).containsExactly(
            "a/build.gradle",
            "build.gradle",
            "buildSrc/build.gradle",
            "buildSrc/src/main/groovy/demo.convention.gradle",
            "gradle.properties",
            "gradle/conventions.gradle",
            "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties",
            "settings.gradle",
        )
        assertThat(File(build.dir, "buildSrc/build")).isDirectory() // there, but not a build file
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `regeneration command repeats the wrapper, init scripts and manifest directory`(version: String) {
        val build = multiProject(version)
        build.run("hierographManifest")
        assertThat(build.manifest().strings("regenerate")).containsExactly("gradle", "-q", "hierographManifest")

        build.file("gradlew", "#!/bin/sh")
        val manifestDir = File(tmp, "state")
        build.run("hierographManifest", "-Phierograph.manifestDir=${manifestDir.path}")
        assertThat(File(build.dir, ".hierograph/manifest.json").readText()).doesNotContain("./gradlew")
        assertThat(build.manifest(manifestDir).strings("regenerate"))
            .containsExactly("./gradlew", "-q", "-Phierograph.manifestDir=${manifestDir.path}", "hierographManifest")
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `the init script applies the plugin without changing the project`(version: String) {
        val build = multiProject(version)
        build.run("hierographManifest")
        val viaSettings = build.manifest()

        // The same build without the plugin in its settings, through the shipped init script.
        val settings = File(build.dir, "settings.gradle.kts")
        settings.writeText(settings.readText().replace("plugins { id(\"io.hierograph\") }\n", ""))
        val init = build.initScript()
        build.run("--init-script", init.path, "hierographManifest")
        val viaInitScript = build.manifest()

        assertThat(viaInitScript.get("modules")).isEqualTo(viaSettings.get("modules"))
        assertThat(viaInitScript.strings("regenerate")).containsExactly("gradle", "-q", "--init-script", init.absolutePath, "hierographManifest")

        // Applied twice — settings and init script — the second application is ignored.
        settings.writeText("plugins { id(\"io.hierograph\") }\n" + settings.readText())
        val twice = build.run("--init-script", init.path, "hierographManifest")
        assertThat(twice.task(":hierographManifest")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(build.manifest().get("modules")).isEqualTo(viaSettings.get("modules"))
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `default coordinates fall back to the root project name`(version: String) {
        val build = build(version).apply {
            file(
                "settings.gradle.kts",
                """
                plugins { id("io.hierograph") }
                rootProject.name = "plain"
                include("a:util", "b:util")
                """,
            )
            file("build.gradle.kts", "plugins { java }")
            file("a/util/build.gradle.kts", "plugins { java }")
            file("b/util/build.gradle.kts", "plugins { java }")
        }

        build.run("hierographManifest")

        assertThat(build.manifest().modules().map { it.str("gav") })
            .containsExactly("plain.a:util:unspecified", "plain.b:util:unspecified", "plain:plain:unspecified")
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `an unresolvable dependency fails the task naming the project`(version: String) {
        val build = multiProject(version)
        File(build.dir, "app/build.gradle.kts").appendText("dependencies { implementation(\"com.example:missing:9\") }\n")

        val result = build.fail("hierographManifest")

        assertThat(result.output).contains("hierographManifest: cannot resolve the dependencies of :app (compileClasspath)")
        assertThat(File(build.dir, ".hierograph/manifest.json")).doesNotExist()
    }

    @ParameterizedTest
    @MethodSource("versions")
    fun `hierograph skip skips the task`(version: String) {
        val build = multiProject(version)

        val result = build.run("hierographManifest", "-Dhierograph.skip=true")

        assertThat(result.output).contains("hierographManifest skipped (hierograph.skip=true)")
        assertThat(File(build.dir, ".hierograph")).doesNotExist()
    }
}
