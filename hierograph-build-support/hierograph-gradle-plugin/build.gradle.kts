import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// The Gradle plugin that writes `.hierograph/manifest.json`. A build of its own, outside the Maven reactor; the
// build-tool-independent `io.hierograph.manifest` library comes from the Maven build (`mvn install` for snapshots,
// Maven Central for releases). See spec/hierograph-gradle-plugin.md.

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.0"
    `maven-publish`
    signing
}

val isRelease = providers.gradleProperty("release").isPresent
if (isRelease) {
    require(!version.toString().endsWith("-SNAPSHOT")) { "-Prelease needs a release version, not $version" }
}

repositories {
    mavenCentral()
    // io.hierograph.manifest of the same version: a snapshot after `mvn install`, a release right after
    // `mvn deploy -Prelease`, before Central has published it.
    mavenLocal()
}

dependencies {
    implementation("io.hierograph:io.hierograph.manifest:$version")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testImplementation("com.google.code.gson:gson:2.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // A plugin runs against the Kotlin stdlib that Gradle embeds: 2.0 from Gradle 8.11, the minimum.
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
    }
}

gradlePlugin {
    website = "https://github.com/code-kontor/hierograph"
    vcsUrl = "https://github.com/code-kontor/hierograph"
    plugins {
        create("hierograph") {
            id = "io.hierograph"
            implementationClass = "io.hierograph.gradle.plugin.HierographSettingsPlugin"
            displayName = "Hierograph"
            description = "Writes the Hierograph build manifest (.hierograph/manifest.json) for a Gradle build."
        }
    }
}

tasks.processResources {
    val pluginVersion = version.toString()
    inputs.property("version", pluginVersion)
    filesMatching(listOf("**/plugin.properties", "**/hierograph.init.gradle")) {
        filter { it.replace("@version@", pluginVersion) }
    }
}

// The Gradle versions the TestKit tests run against: the minimum (8.11) and the one building this plugin.
val testedGradleVersions = listOf("8.11", gradle.gradleVersion)

tasks.test {
    useJUnitPlatform()
    systemProperty("hierograph.test.gradleVersions", testedGradleVersions.joinToString(","))
    providers.gradleProperty("hierograph.test.gradleVersions").orNull?.let { systemProperty("hierograph.test.gradleVersions", it) }
    maxParallelForks = 2
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "Hierograph Gradle Plugin"
            description = "A Gradle settings plugin that writes the Hierograph build manifest without building the project."
            url = "https://github.com/code-kontor/hierograph"
            licenses {
                license {
                    name = "Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
            developers {
                developer {
                    name = "Gerd Wuetherich"
                    email = "gerd.wuetherich@code-kontor.io"
                }
            }
            scm {
                url = "https://github.com/code-kontor/hierograph"
                connection = "scm:git:https://github.com/code-kontor/hierograph.git"
            }
        }
    }
}

signing {
    // Releases only (-Prelease), with the GPG key the Maven release uses (gpg-agent).
    isRequired = isRelease
    if (isRelease) {
        useGpgCmd()
        sign(publishing.publications)
    }
}

// ---- Release: `./gradlew publish -Prelease` uploads the plugin and its marker to the Central Portal --------------
//
// The publications go to a local staging repository, which is zipped into a Central bundle and uploaded as a
// USER_MANAGED deployment: reviewed and published in the Portal UI, like the Maven upload. The Portal user token is
// the one in the Maven settings' `central` server, here as Gradle properties `centralUsername` / `centralPassword`
// (~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_centralUsername / ORG_GRADLE_PROJECT_centralPassword).

val centralStaging = layout.buildDirectory.dir("central-staging")

publishing {
    repositories {
        maven {
            name = "centralStaging"
            url = uri(centralStaging)
        }
    }
}

val cleanCentralStaging by tasks.registering(Delete::class) {
    delete(centralStaging)
}

tasks.withType<PublishToMavenRepository>().matching { it.name.endsWith("ToCentralStagingRepository") }.configureEach {
    dependsOn(cleanCentralStaging)
}

val centralBundle by tasks.registering(Zip::class) {
    dependsOn("publishAllPublicationsToCentralStagingRepository")
    from(centralStaging)
    exclude("**/maven-metadata*")
    archiveFileName = "central-bundle-$version.zip"
    destinationDirectory = layout.buildDirectory.dir("central")
}

val publishToCentralPortal by tasks.registering {
    description = "Uploads the signed bundle to the Central Portal as a deployment to review and publish there."
    dependsOn(centralBundle)
    val bundle = centralBundle.flatMap { it.archiveFile }
    val username = providers.gradleProperty("centralUsername")
    val password = providers.gradleProperty("centralPassword")
    val deploymentName = "hierograph-gradle-plugin $version"
    doLast {
        val token = Base64.getEncoder().encodeToString("${username.get()}:${password.get()}".toByteArray())
        val file = bundle.get().asFile
        val boundary = "----hierograph" + System.nanoTime()
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"${file.name}\"\r\n".toByteArray())
            write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(file.readBytes())
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val name = URLEncoder.encode(deploymentName, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(
            uri("https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED&name=$name"),
        )
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw GradleException("Central Portal upload failed (${response.statusCode()}): ${response.body()}")
        }
        logger.lifecycle("Uploaded ${file.name} to the Central Portal, deployment ${response.body()}: review and publish it at https://central.sonatype.com/publishing/deployments")
    }
}

tasks.named("publish") {
    if (isRelease) dependsOn(publishToCentralPortal)
}
