plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

// Robolectric's Android jar, resolved and cached by Gradle like every other dependency, rather
// than downloaded by Robolectric during the test run.
val robolectricSdk: Configuration by configurations.creating { isTransitive = false }

/** Points Robolectric at the jar's folder, and makes the jar an input of the test task. */
class RobolectricSdk(@get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) val jars: FileCollection) :
    CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Drobolectric.dependency.dir=${jars.singleFile.parentFile.absolutePath}")
}

android {
    namespace = "dev.silver.burble"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.silver.burble"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's SDK 36 sandbox reaches into java.base internals.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            // Offline, Robolectric fails at once on a jar it lacks instead of fetching it.
            it.systemProperty("robolectric.offline", "true")
            it.jvmArgumentProviders.add(RobolectricSdk(robolectricSdk))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")

    // Screenshot tests: `recordRoborazziDebug` writes app/screenshots/, `verifyRoborazziDebug`
    // fails when a screen drifts.
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    // Robolectric 4.17's Android for SDK 36, the only one the tests run. A new Robolectric names
    // another: its tests then fail with "Unable to locate dependency" and the version it wants.
    robolectricSdk("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.76.0")
    // Only its colour utilities, to build wallpaper schemes from a seed in tests.
    testImplementation("com.google.android.material:material:1.14.0") { isTransitive = false }
}
