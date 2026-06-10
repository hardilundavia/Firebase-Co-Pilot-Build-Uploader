plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "io.firebasebuilduploader"
version = "1.0.0"

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
        // Target Android Studio Ladybug | 2024.2.1 — change to match your installed version
        androidStudio("2024.2.1.11")

        // Required bundled plugins
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("org.jetbrains.plugins.gradle")
        bundledPlugin("com.intellij.java")

        instrumentationTools()
    }

    // Firebase / Google Auth
    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")

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
