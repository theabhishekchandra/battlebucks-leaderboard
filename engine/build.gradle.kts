import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No version: AGP 9 ships Kotlin support, so KGP is already on the build classpath and
    // re-declaring a version here fails plugin resolution.
    id("org.jetbrains.kotlin.jvm")
}

java {
    // Must match :app's compileOptions. Gradle fails the build on a Java/Kotlin JVM-target
    // mismatch, and bytecode newer than the app's target will not link on device.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // `api`, not `implementation`: Flow is part of this module's public contract.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
