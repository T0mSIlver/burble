// A caching mirror of Google's Maven, Maven Central and the Gradle Plugin Portal, set per machine
// as `starbridge.mavenMirror=http://host:port` in ~/.gradle/gradle.properties (docs/ci.md of
// T0mSIlver/starbridge). The public repositories stay after it, so any machine resolves as before.
pluginManagement {
    val mirror = providers.gradleProperty("starbridge.mavenMirror").orNull?.trim()?.trimEnd('/')?.takeUnless { it.isEmpty() }
    repositories {
        if (mirror != null) {
            for (path in listOf("google", "maven-central", "gradle-plugins")) maven {
                url = uri("$mirror/$path/")
                isAllowInsecureProtocol = true
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val mirror = providers.gradleProperty("starbridge.mavenMirror").orNull?.trim()?.trimEnd('/')?.takeUnless { it.isEmpty() }
    repositories {
        if (mirror != null) {
            for (path in listOf("google", "maven-central")) maven {
                url = uri("$mirror/$path/")
                isAllowInsecureProtocol = true
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "rambler-bubble"
include(":app")
