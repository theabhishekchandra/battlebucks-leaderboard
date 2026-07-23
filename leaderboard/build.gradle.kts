import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No version: AGP 9 ships Kotlin support, so KGP is already on the build classpath and
    // re-declaring a version here fails plugin resolution.
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // NOTE: there is deliberately no `implementation(project(":engine"))` here.
    // This module defines its own input contract (ScoreUpdate) and knows nothing about who
    // produces it. See README → "Why the modules are split this way".
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
