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
package io.hierograph.itest.gradle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * A small build, `com.example:lib` and `com.example:app` (depending on `lib`), in **both** Maven and Gradle form
 * in one directory, so the two manifest producers can be compared and the server can load the Gradle one.
 *
 * The dependencies are chosen so that both tools give the same classpaths: Maven orders by declaration, Gradle
 * by configuration (compile, then runtime additions), and Gradle's test classpath has no `compileOnly` entries.
 *
 * The Gradle side applies the plugin as a user without changing the project would: through the init script the
 * plugin jar ships, resolving the plugin from `mavenLocal()`. That needs the Gradle plugin published there first
 * (`./gradlew publishToMavenLocal` in `hierograph-build-support/hierograph-gradle-plugin`), and for the Maven side
 * the Maven plugin installed (`mvn install`). The tests using it are opt-in: `-Dhierograph.itest.gradle=true`.
 */
class GradleFixture(val root: Path) {

    companion object {
        const val ENABLED_PROPERTY = "hierograph.itest.gradle"

        private val json = JsonMapper()

        /** The Hierograph version under test; the failsafe configuration passes the project version. */
        val version: String = System.getProperty("hierograph.version") ?: error("system property hierograph.version is not set")

        private val localRepository: Path =
            Path.of(System.getProperty("maven.repo.local") ?: "${System.getProperty("user.home")}/.m2/repository")

        /** The Gradle plugin's own build, whose wrapper the fixture borrows. */
        private val pluginBuild: Path = Path.of("../../hierograph-build-support/hierograph-gradle-plugin").absolute().normalize()

        fun readJson(file: Path): JsonNode = json.readTree(file.toFile())
    }

    /** Writes both forms of the build, the Gradle wrapper and the shipped init script (outside [root]). */
    fun create(): GradleFixture {
        if (root.exists()) root.toFile().deleteRecursively()
        root.createDirectories()
        write("pom.xml", pom("demo", "pom", modules = listOf("lib", "app")))
        write(
            "lib/pom.xml",
            pom(
                "lib", "jar",
                dependencies = listOf(
                    dependency("org.slf4j", "slf4j-api", "2.0.16", "compile"),
                    dependency("org.opentest4j", "opentest4j", "1.3.0", "test"),
                    dependency("org.slf4j", "slf4j-simple", "2.0.16", "runtime"),
                ),
            ),
        )
        write("app/pom.xml", pom("app", "jar", dependencies = listOf(dependency("com.example", "lib", "1.0", "compile"))))

        write("settings.gradle.kts", "rootProject.name = \"demo\"\ninclude(\"lib\", \"app\")\n")
        write("build.gradle.kts", "allprojects {\n    group = \"com.example\"\n    version = \"1.0\"\n    repositories { mavenCentral() }\n}\n")
        write(
            "lib/build.gradle.kts",
            """
            plugins { `java-library` }
            dependencies {
                api("org.slf4j:slf4j-api:2.0.16")
                runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
                testImplementation("org.opentest4j:opentest4j:1.3.0")
            }
            """.trimIndent() + "\n",
        )
        write("app/build.gradle.kts", "plugins { java }\ndependencies { implementation(project(\":lib\")) }\n")
        // Release the daemons this test starts soon after it ends.
        write("gradle.properties", "org.gradle.daemon.idletimeout=60000\n")

        write("lib/src/main/java/com/example/lib/Lib.java", "package com.example.lib;\npublic class Lib {}\n")
        write("app/src/main/java/com/example/app/App.java", "package com.example.app;\npublic class App { com.example.lib.Lib lib; }\n")

        for (file in listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")) {
            val target = root.resolve(file)
            target.parent.createDirectories()
            Files.copy(pluginBuild.resolve(file), target, REPLACE_EXISTING)
        }
        root.resolve("gradlew").toFile().setExecutable(true)
        return this
    }

    /** The init script from the published plugin jar, extracted next to [root]. */
    val initScript: Path by lazy {
        val jar = localRepository.resolve("io/hierograph/hierograph-gradle-plugin/$version/hierograph-gradle-plugin-$version.jar")
        check(Files.isRegularFile(jar)) { "$jar is missing: run ./gradlew publishToMavenLocal in $pluginBuild first" }
        val script = root.resolveSibling("${root.fileName}-init/hierograph.init.gradle")
        script.parent.createDirectories()
        ZipFile(jar.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("io/hierograph/gradle/plugin/hierograph.init.gradle")).use { Files.copy(it, script, REPLACE_EXISTING) }
        }
        script
    }

    /** Runs `./gradlew -q --init-script <shipped> [args] hierographManifest`. */
    fun gradle(vararg args: String) = run(listOf("./gradlew", "-q", "--init-script", initScript.toString()) + args + "hierographManifest")

    /** Runs the Maven plugin's `manifest` goal. */
    fun maven(vararg args: String) {
        val home = System.getProperty("maven.home")
        val mvn = if (home != null) "$home/bin/mvn" else "mvn"
        run(listOf(mvn, "-q", "-B") + args + "io.hierograph:hierograph-maven-plugin:$version:manifest")
    }

    fun write(path: String, content: String): Path = root.resolve(path).also {
        it.parent.createDirectories()
        it.writeText(content)
    }

    private fun run(command: List<String>) {
        val log = Files.createTempFile("hierograph-itest", ".log")
        val process = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        check(process.waitFor(10, TimeUnit.MINUTES)) { "timed out: $command" }
        check(process.exitValue() == 0) { "failed (${process.exitValue()}): $command\n" + Files.readString(log) }
    }

    private fun pom(artifactId: String, packaging: String, modules: List<String> = emptyList(), dependencies: List<String> = emptyList()) =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>$artifactId</artifactId>
            <version>1.0</version>
            <packaging>$packaging</packaging>
            <modules>${modules.joinToString("") { "<module>$it</module>" }}</modules>
            <dependencies>${dependencies.joinToString("")}</dependencies>
        </project>
        """.trimIndent() + "\n"

    private fun dependency(groupId: String, artifactId: String, version: String, scope: String) =
        "<dependency><groupId>$groupId</groupId><artifactId>$artifactId</artifactId><version>$version</version><scope>$scope</scope></dependency>"
}
