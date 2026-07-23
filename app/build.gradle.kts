plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.abhishek.battlebucks"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.abhishek.battlebucks"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Compose UI tests run on the JVM under Robolectric, so they need real resources.
            isIncludeAndroidResources = true
        }
    }
}

composeCompiler {
    // Classes from a module that is not compiled by the Compose compiler are treated as UNSTABLE
    // by default. `:leaderboard` is a plain Kotlin module, so without this its entries would be
    // unstable, every LazyColumn item would be considered "maybe changed", and per-item skipping
    // — the whole point of the reducer reusing row instances — would silently never happen.
    //
    // The alternatives were: annotate the domain with @Immutable (drags a Compose dependency into
    // a pure module, which defeats the module split), or re-map to UI models in :app (real code,
    // real allocations, for a mapping that changes nothing). A config file states the fact where
    // it belongs — in the module that cares about Compose.
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("app/compose_compiler_config.conf"),
    )

    // Uncomment to have the compiler report exactly which composables are skippable/restartable:
    // reportsDestination = layout.buildDirectory.dir("compose_compiler")
    // metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":leaderboard"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // collectAsStateWithLifecycle: stops UI collection at STOPPED instead of leaking a
    // subscription for as long as the composition lives.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // ProcessLifecycleOwner: lets the match pause when the whole app goes to background.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Compose UI tests on the JVM: no emulator, so they run in the same CI step as everything else.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
