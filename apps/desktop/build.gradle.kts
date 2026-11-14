import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Kelo for the desktop (Windows first; macOS and Linux build from the same code). Compose Multiplatform for Desktop.
//
//   ./gradlew run                      the app (JAVA_HOME = a JDK 17)
//   ./gradlew test                     the unit tests (shared rules and the desktop's own)
//   ./gradlew packageDmg               a macOS disk image (on a Mac)
//   ./gradlew packageMsi packageExe    Windows installers (only on Windows; see .github/workflows/desktop-windows.yml)
//   ./gradlew createDistributable      the app folder the MSIX for the Microsoft Store is packed from (packaging/windows/)
//
// The Android app (apps/android/kelo) is the reference. Its plain Kotlin files (the API client, the models, the rules) and the Compose
// files that need nothing from Android (the character, the icons) are the Kotlin Multiplatform module apps/shared/ (package
// chat.kelo.app), which both apps use as a composite build (settings.gradle.kts: includeBuild); its unit tests run here too
// (`./gradlew test` also runs shared's desktopTest). Nothing in apps/android/kelo is changed by this build.
// The text is apps/shared/strings (Android format), converted at build time into Compose resources (chat.kelo.app.res.Res; apps/shared/build.gradle.kts).

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "chat.kelo"
/**
 * Two numbers (docs/versioning.md):
 * - the release people see, `version` in the repository's version.json (e.g. 0.9.0), with `build` and `min_server`, compiled
 *   into chat.kelo.desktop.core.ReleaseInfo (shown in My, sent as X-Kelo-App, compared with the server's X-Kelo-Server);
 * - the installers' package number, `packageVersion` in apps/desktop/version.properties (1.0.8, ...): MSI needs MAJOR.MINOR.BUILD
 *   with MAJOR >= 1, macOS too, and an update needs a higher one; the MSIX adds a fourth part (packaging/windows/make_msix.ps1).
 *   `-PkeloVersion=X.Y.Z` overrides it for a one-off build only.
 */
val releaseInfo: Map<String, Any?> = run {
    @Suppress("UNCHECKED_CAST")
    groovy.json.JsonSlurper().parse(rootProject.file("../../version.json")) as Map<String, Any?>
}
val releaseVersion = releaseInfo["version"] as String
val releaseBuild = (releaseInfo["build"] as Number).toInt()
val minServer = releaseInfo["min_server"] as String
/**
 * Which commit this build's source came from, for the version line in My (it links to that commit on GitHub; docs/versioning.md):
 * the full sha and whether tracked files under the app's inputs differ from it. The build scripts pass them (-PkeloCommit=<sha>
 * -PkeloDirty=true|false; the reproducible build has no .git inside its container); without them git answers, and with no git (a
 * source tarball) the commit is "" and nothing is shown. Never fails the build.
 */
fun gitAnswer(vararg args: String): String {
    val root = rootProject.file("../..")
    if (!File(root, ".git").exists()) return ""
    return try {
        providers.exec { commandLine("git", *args); workingDir = root; isIgnoreExitValue = true }.standardOutput.asText.get().trim()
    } catch (e: Exception) {
        ""
    }
}
val sourceCommit: String = ((project.findProperty("keloCommit") as String?) ?: gitAnswer("rev-parse", "HEAD")).filter { it.isLetterOrDigit() }
val sourceDirty: Boolean = (project.findProperty("keloDirty") as String?)?.trim()?.lowercase()?.let { it == "true" }
    ?: gitAnswer("status", "--porcelain", "--untracked-files=no", "--", "apps/desktop", "apps/shared", "version.json").isNotEmpty()

val packageProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val keloVersion = (findProperty("keloVersion") as String?) ?: packageProps.getProperty("packageVersion")
version = keloVersion

/**
 * Android unit tests that run here too, against this app's own copies of the Android code (apps/desktop/COPIES.md: app/chat/SignOutRules.kt
 * ...) and the strings: they stay in apps/android/kelo, because apps/shared/ has neither. Relative to apps/android/kelo/app/src/test/java/chat/kelo/app.
 */
val androidTestsRunHere = listOf(
    "chat/ChatScrollTest.kt",
    "chat/ModelChooserRulesTest.kt",
    "chat/ReportRulesTest.kt",
    "core/PairingLinksTest.kt",
    "my/BriefingSettingsRulesTest.kt",
    "my/BriefingSourcesRulesTest.kt",
    "my/OperatorRulesTest.kt",
)

/** FieldWrapScan (the helper that scans source files for fields that can wrap) is apps/shared/'s test file, used by SingleLineFieldsTest here too. */
val appTestsDir = layout.buildDirectory.dir("generated/appTests/kotlin")
val syncAppTests by tasks.registering(Sync::class) {
    from(rootProject.file("../shared/src/desktopTest/kotlin")) { include("chat/kelo/app/ui/components/FieldWrapScan.kt") }
    from(rootProject.file("../android/kelo/app/src/test/java")) {
        androidTestsRunHere.forEach { include("chat/kelo/app/$it") }
        // these tests name text as R.string.x (a number on Android); here it is chat.kelo.app.res.Res.string.x (Compose resources)
        val androidText = Regex("(?<![\\w.])R\\.(string|plurals|array)\\.")
        filter { line -> if (line.trim() == "import chat.kelo.app.R") "import chat.kelo.app.res.*" else line.replace(androidText, "Res.$1.") }
    }
    inputs.property("androidTextRename", "R.string -> Res.string v1")   // the copy is changed by the filter above: a new rule re-copies
    into(appTestsDir)
}

val appVersionDir = layout.buildDirectory.dir("generated/appVersion/kotlin")
val generateAppVersion by tasks.registering {
    description = "Writes chat.kelo.desktop.core.ReleaseInfo from ../../version.json and version.properties."
    val out = appVersionDir
    val values = listOf(releaseVersion, releaseBuild.toString(), minServer, keloVersion, sourceCommit, sourceDirty.toString())
    inputs.property("values", values)
    outputs.dir(out)
    doLast {
        val f = out.get().file("chat/kelo/desktop/core/ReleaseInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            |package chat.kelo.desktop.core
            |
            |/** Generated by build.gradle.kts from version.json and apps/desktop/version.properties (docs/versioning.md). Do not edit. */
            |object ReleaseInfo {
            |    /** The release people see (version.json `version`) */
            |    const val VERSION = "${values[0]}"
            |    /** version.json `build` */
            |    const val BUILD = ${values[1]}
            |    /** The oldest server release this app works with (version.json `min_server`) */
            |    const val MIN_SERVER = "${values[2]}"
            |    /** The installers' package number (apps/desktop/version.properties), what /download/latest.json compares */
            |    const val PACKAGE = "${values[3]}"
            |    /** The commit the source came from (40 hex, "" = the build did not say) and whether files differed from it (SourceLink) */
            |    const val SOURCE_COMMIT = "${values[4]}"
            |    const val SOURCE_DIRTY = ${values[5]}
            |}
            |""".trimMargin(),
        )
    }
}

sourceSets {
    main {
        kotlin.srcDir(appVersionDir)
    }
    test {
        kotlin.srcDir(appTestsDir)
    }
}

tasks.named("compileKotlin") { dependsOn(generateAppVersion) }
tasks.named("compileTestKotlin") { dependsOn(syncAppTests) }

// jars, zips and AARs of this build: no file times, a fixed file order, so the same source gives the same bytes (docs/verify-builds.md)
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("chat.kelo:shared")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Windows DPAPI (the saved sign-in is sealed to the Windows user) and the registry
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    // the QR code for connecting a new device (My → My devices), the same encoder the Android app uses
    implementation("com.google.zxing:core:3.5.3")
    // KE2E (docs/security/e2e-and-phone-key.md): HPKE with Export and ChaCha20-Poly1305, lightweight API only (chat.kelo.app.core.e2e)
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.test {
    useJUnit()
    // the unit tests of apps/shared/ (the code both apps use) run with the desktop's
    dependsOn(gradle.includedBuild("shared").task(":desktopTest"))
    // CopiesTest (COPIES.md) reads the Android / shared originals listed there, and the names of all Android and shared .kt files (a new same-name file
    // on the desktop must be listed): only those make it run again, not every Android change
    val copies = layout.projectDirectory.file("COPIES.md")
    inputs.file(copies).withPropertyName("copiesList")
    val repo = rootProject.layout.projectDirectory.dir("..")
    val originals = providers.fileContents(copies).asText.map { md ->
        md.lines().filter { it.startsWith("| desktop/") }
            .flatMap { it.trim().trim('|').split("|").getOrElse(1) { "" }.split(",") }
            .map { it.trim() }.filter { it.startsWith("android/") || it.startsWith("shared/") }.distinct()
            .map { repo.file(it).asFile }
    }
    inputs.files(originals).withPropertyName("androidOriginals").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("androidKtNames", provider {
        (fileTree(repo.dir("android/kelo/app/src")) { include("main/java/**/*.kt", "test/java/**/*.kt") } +
            fileTree(repo.dir("shared/src")) { include("**/*.kt") }).map { it.name }.sorted().joinToString(",")
    })
    // headless: the tests draw with Skia off screen (no window)
    systemProperty("java.awt.headless", "true")
}

/** Renders the app icons (PNG, ICO, ICNS source PNGs, MSIX tiles) from Kelo's character into packaging/icons. */
tasks.register<JavaExec>("renderIcons") {
    group = "kelo"
    description = "Draws the icons from the character (packaging/icons)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("chat.kelo.desktop.tools.RenderIconsKt")
    args(rootProject.file("packaging/icons").absolutePath)
    systemProperty("java.awt.headless", "true")
}

compose.desktop {
    application {
        mainClass = "chat.kelo.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Xmx512m")

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Kelo"
            packageVersion = keloVersion
            description = "Kelo - your own AI agent, on your desktop"
            vendor = "Kelo"
            copyright = "© 2026 Kelo"
            // what the JVM needs: TLS with modern ciphers (jdk.crypto.ec), the tray and windows (java.desktop), preferences
            modules("java.desktop", "java.logging", "java.naming", "java.prefs", "java.net.http", "jdk.crypto.ec", "jdk.unsupported",
                "jdk.accessibility", "java.management")

            windows {
                iconFile.set(project.file("packaging/icons/kelo.ico"))
                menuGroup = "Kelo"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // never change: Windows uses it to tell an update of Kelo from a different program
                upgradeUuid = "6B0E6C2A-3F7D-4C55-9D7E-2A8F1B4C9E31"
            }
            macOS {
                iconFile.set(project.file("packaging/icons/kelo.icns"))
                bundleID = "chat.kelo.desktop"
                dockName = "Kelo"
            }
            linux {
                iconFile.set(project.file("packaging/icons/kelo-512.png"))
            }
        }
    }
}

/** Draws the Microsoft Store's optional images (box art, app tile, poster art) from the character into store/logos. */
tasks.register<JavaExec>("renderStoreLogos") {
    group = "kelo"
    description = "Draws the Store logo images from the character (store/logos)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("chat.kelo.desktop.tools.StoreLogosKt")
    args(rootProject.file("store/logos").absolutePath)
    systemProperty("java.awt.headless", "true")
}
