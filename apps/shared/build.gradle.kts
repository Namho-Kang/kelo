import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The code Android and the desktop app share (package chat.kelo.app.*): the API client, models, rules, the character and icons.
// Both apps use it as a composite build (apps/android/kelo/settings.gradle.kts, apps/desktop/settings.gradle.kts: includeBuild("../../shared")).
// Targets: Android (library) and the desktop JVM. Its unit tests run on the desktop JVM target: ./gradlew desktopTest

plugins {
    kotlin("multiplatform") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("com.android.library") version "8.10.1"
}

// the apps' text (apps/shared/strings, Android format) as Compose resources: gradle/strings-to-compose.gradle.kts
apply(from = "gradle/strings-to-compose.gradle.kts")
@Suppress("UNCHECKED_CAST")
val keloStrings = extra["keloStrings"] as Provider<Directory>   // the task's output: depending on it runs the task

compose.resources {
    publicResClass = true
    packageOfResClass = "chat.kelo.app.res"
    generateResClass = always
    customDirectory("commonMain", keloStrings)
}

group = "chat.kelo"
version = "1"

// jars, zips and AARs of this build: no file times, a fixed file order, so the same source gives the same bytes (docs/verify-builds.md)
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); freeCompilerArgs.add("-Xjvm-default=all") } }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            api(compose.components.resources)
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            api("com.squareup.okhttp3:okhttp:4.12.0")
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.8.4")   // the ViewModel base class (same package androidx.lifecycle)
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")   // collectAsStateWithLifecycle, lifecycle effects
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")   // viewModel { }
            api("org.bouncycastle:bcprov-jdk18on:1.86")
            implementation("com.google.zxing:core:3.5.3")   // the pairing QR (My -> devices)
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
                implementation(compose.desktop.currentOs)
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}

// the golden test of the strings conversion compares what the converter wrote for src/desktopTest/strings-conversion/in with expected/
tasks.named<Test>("desktopTest") {
    dependsOn("convertStringsFixtures")
    val expected = layout.projectDirectory.dir("src/desktopTest/strings-conversion/expected")
    inputs.dir(expected).withPropertyName("stringsExpected")
    systemProperty("kelo.strings.expected", expected.asFile.path)
    systemProperty("kelo.strings.actual", layout.buildDirectory.dir("generated/stringsFixtures").get().asFile.path)
    timeout.set(Duration.ofMinutes(40))   // a test that hangs fails the build instead of holding a CI runner for hours
    if (System.getenv("CI") != null) testLogging { events("started", "failed", "skipped") }   // CI logs name the test that was running
}

android {
    namespace = "chat.kelo.shared"
    compileSdk = 36
    buildToolsVersion = "36.0.0"   // the one the app and the pinned build image (apps/android/reproducible) use
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
