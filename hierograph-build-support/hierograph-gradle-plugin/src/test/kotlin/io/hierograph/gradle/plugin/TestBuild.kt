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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.util.Properties

/** The Gradle versions under test, from the build (`hierograph.test.gradleVersions`): the minimum and the current one. */
fun gradleVersions(): List<String> =
    System.getProperty("hierograph.test.gradleVersions", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * A Gradle build under test in [dir], run by TestKit on [gradleVersion] with the plugin under test on the plugin
 * classpath. The project cache goes to [projectCacheDir], outside [dir], so a test can tell what the run wrote.
 */
class TestBuild(val dir: File, private val gradleVersion: String, private val projectCacheDir: File) {

    fun file(path: String, content: String): File =
        File(dir, path).normalize().apply { parentFile.mkdirs(); writeText(content.trimIndent() + "\n") }

    fun run(vararg args: String): BuildResult = runner(*args).build()

    fun fail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
        .withGradleVersion(gradleVersion)
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(listOf("--stacktrace", "--project-cache-dir", projectCacheDir.path) + args)
        .forwardOutput()

    fun manifest(dir: File = File(this.dir, ".hierograph")): JsonObject =
        JsonParser.parseString(File(dir, "manifest.json").readText()).asJsonObject

    /** All files below [dir], relative and sorted. */
    fun files(): List<String> = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    /**
     * An init script equal to the one the plugin jar ships, except that its `initscript` block puts the plugin
     * under test on the classpath instead of resolving the published jar.
     */
    fun initScript(): File {
        val shipped = javaClass.classLoader.getResource(HierographSettingsPlugin.INIT_SCRIPT_RESOURCE)!!.readText()
        val body = Regex("(?s)initscript \\{.*?\\n}\\n").replace(shipped, "")
        val classpath = pluginClasspath().joinToString(", ") { "'" + it.invariantSeparatorsPath + "'" }
        return file("../init/hierograph.init.gradle", "initscript { dependencies { classpath files($classpath) } }\n$body")
    }

    private fun pluginClasspath(): List<File> {
        val properties = Properties()
        javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use(properties::load)
        return properties.getProperty("implementation-classpath").split(File.pathSeparator).map(::File)
    }
}

// Manifest JSON accessors for assertions.

fun JsonObject.modules(): List<JsonObject> = getAsJsonArray("modules").map { it.asJsonObject }

fun JsonObject.module(name: String): JsonObject = modules().single { it.get("name").asString == name }

fun JsonObject.strings(key: String): List<String> = getAsJsonArray(key).map { it.asString }

fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

/** A module's classpath as `gav` strings, internal entries marked with a leading `*`. */
fun JsonObject.classpath(key: String): List<String> = getAsJsonArray(key).map {
    val o = it.asJsonObject
    (if (o.get("internal").asBoolean) "*" else "") + o.get("gav").asString
}

fun JsonObject.buildFiles(root: File): List<String> =
    getAsJsonArray("buildFiles").map { File(it.asJsonObject.get("path").asString).relativeTo(root.canonicalFile).invariantSeparatorsPath }
