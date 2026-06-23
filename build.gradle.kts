plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "io.firebasebuilduploader"
version = "2.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}
dependencies {
    intellijPlatform {
        // Target Android Studio 2025.1 — compile SDK for current Platform APIs
        androidStudio("2025.1.3.7")

        // Required bundled plugins
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("org.jetbrains.plugins.gradle")
        bundledPlugin("com.intellij.java")
    }

    // Firebase / Google Auth
    implementation(platform("com.google.auth:google-auth-library-bom:1.37.1"))
    implementation("com.google.auth:google-auth-library-oauth2-http")
    // Override transitive commons-codec to fix WS-2019-0379 (versions < 1.13)
    implementation("commons-codec:commons-codec:1.21.0")
    // Override transitive Guava to fix CVE-2023-2976 (versions < 32.0.1)
    implementation("com.google.guava:guava:32.1.3-jre")

    // HTTP client for Firebase Distribution API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines for async build + upload
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
