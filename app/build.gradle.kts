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

/** The licence texts the Licences page shows, copied from the repository's own: assets/licences/. */
abstract class LicenceAssets : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val texts: ConfigurableFileCollection
    @get:OutputDirectory abstract val out: DirectoryProperty

    @TaskAction fun copy() {
        val dir = out.get().asFile.resolve("licences").apply { deleteRecursively(); mkdirs() }
        texts.forEach { it.copyTo(dir.resolve(if (it.name == "LICENSE") "GPL-3.0.txt" else it.name), overwrite = true) }
    }
}

val licenceAssets = tasks.register<LicenceAssets>("licenceAssets") {
    texts.from(rootProject.file("LICENSE"), rootProject.fileTree("licenses") { include("*.txt") })
    out.set(layout.buildDirectory.dir("generated/licences"))
}

androidComponents {
    onVariants { it.sources.assets?.addGeneratedSourceDirectory(licenceAssets, LicenceAssets::out) }
}

android {
    namespace = "dev.silver.burble"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.silver.burble"
        minSdk = 31
        targetSdk = 36
        // Release builds pass -PversionName from the tag (v1.2.3 gives 1.2.3).
        val version = providers.gradleProperty("versionName").orNull ?: "0.1.0"
        versionName = version
        // 1.2.3 is 10203: the in-app updater only installs a higher versionCode.
        versionCode = version.substringBefore('-').split('.').map { it.toInt() }
            .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
        // -PupdatesUrl points the updater at a fake release for tests.
        val updates = providers.gradleProperty("updatesUrl").orNull
            ?: "https://api.github.com/repos/T0mSIlver/burble/releases/latest"
        buildConfigField("String", "UPDATES_URL", "\"$updates\"")
    }

    signingConfigs {
        create("release") {
            val keystore = releaseKeystore() ?: return@create
            storeFile = keystore.file
            storePassword = keystore.password
            keyAlias = keystore.alias
            keyPassword = keystore.keyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only with the release key's environment (release.yml's); without it packageRelease
            // refuses, below, rather than sign with the debug key.
            signingConfig = if (releaseKeystore() != null) signingConfigs.getByName("release") else null
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

// A release APK signed with any other key couldn't update installed copies, or would pass a debug
// key off as Burble's. Lint and tests of the release variant still run without the key.
tasks.matching { it.name == "packageRelease" }.configureEach {
    val signed = releaseKeystore() != null
    doFirst {
        if (!signed) throw GradleException("A release build needs BURBLE_KEYSTORE and its passwords, which only release.yml sets")
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
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

class Keystore(val file: File, val password: String, val alias: String, val keyPassword: String)

/**
 * The release key, only from BURBLE_KEYSTORE (a path) and its passwords, which release.yml sets
 * from the repository's secrets. It is never read from disk otherwise, so no local build can
 * sign with it by accident.
 */
fun releaseKeystore(): Keystore? {
    val path = System.getenv("BURBLE_KEYSTORE")?.takeIf { it.isNotEmpty() } ?: return null
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: error("BURBLE_KEYSTORE is set but $name is not")
    return Keystore(File(path), env("BURBLE_KEYSTORE_PASSWORD"), env("BURBLE_KEY_ALIAS"), env("BURBLE_KEY_PASSWORD"))
}
