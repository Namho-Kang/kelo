// Kelo's shared code module: the Kotlin both the Android app (apps/android/kelo) and the desktop app (desktop) use (API client, models,
// rules, the character and icons). Each app includes this folder as a composite build (includeBuild). Versions come from the
// Android catalog so there is one list.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../android/kelo/gradle/libs.versions.toml")) }
    }
}
rootProject.name = "shared"
