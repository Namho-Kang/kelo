pluginManagement {
    repositories {
        // Maven Central first: the plugin portal hands Kotlin's own jars out through GitHub release links, which time out
        // on GitHub's Windows runners now and then; Central serves the same jars directly.
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "kelo-desktop"

// the code Android and the desktop app share (see apps/shared/build.gradle.kts)
includeBuild("../shared")
