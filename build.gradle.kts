import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.io.File
import java.util.Properties

// Not checked in (see .gitignore) - holds this machine's GA4 Measurement Protocol credentials,
// read below and passed through as system properties the same way appVersion is. Missing/absent
// keys resolve to "" rather than failing the build; Analytics.kt treats a blank value as
// analytics-not-configured and simply never sends anything.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

// Shared with the packageMsix task below, so the app-image directory name it reads from and the
// jpackage packageName it's built from can't drift apart.
val appPackageName = "sbs3Dfullscreen"

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    jvm("desktop")

    sourceSets {
        val opencvJarExists = file("libs/opencv/opencv-500.jar").exists()
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                // Same Material icon set (Icons.Filled.Add, Icons.AutoMirrored.Filled.ArrowBack, ...)
                // CameraSync3D's playlist screens use, for a matching look on desktop.
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                implementation("org.jetbrains.compose.components:components-resources:1.11.1")
                // Jackson: same coordinates/version as CameraSync3D (the companion Android app) so the
                // EXIF3D (Desc3d) and playlist YAML formats stay wire-compatible between the two apps.
                implementation("tools.jackson.module:jackson-module-kotlin:3.2.0")
                implementation("tools.jackson.dataformat:jackson-dataformat-yaml:3.2.0")
                // Same coordinates as CameraSync3D's app/build.gradle, so the shared playlist item
                // Composable (synced from Android) can use coil3.compose.SubcomposeAsyncImage identically
                // on both platforms.
                implementation("io.coil-kt.coil3:coil-compose:3.5.0")
                // Same coordinates as CameraSync3D's app/build.gradle, for drag-to-reorder in the shared
                // playlist item list.
                implementation("sh.calvin.reorderable:reorderable:3.1.0")
                // Real OpenCV 5 (matching CameraSync3D's org.opencv:opencv:5.0.0.1) isn't on Maven
                // Central for desktop JVM (only as an Android AAR) - vendor the official Windows
                // build's Java jar locally instead. See libs/opencv/README.md. Declared in commonMain
                // (not desktopMain) so AutoAlignCore.kt - grouped here with the rest of the files
                // synced from CameraSync3D rather than split by which platform types they happen to
                // need - can compile; this project has only one real target (desktop) today, so
                // there's no other-target purity being traded away yet.
                val opencvJar = file("libs/opencv/opencv-500.jar")
                if (opencvJar.exists()) {
                    implementation(files(opencvJar))
                }
            }
            // AutoAlignCore.kt (synced from CameraSync3D) needs org.opencv.* - exclude it from
            // compilation until OpenCV is actually vendored, so the rest of the app (which doesn't
            // need auto-align) still builds in the meantime. tools/sync-from-android.ps1 re-syncs
            // this file unconditionally; only compilation is gated here.
            if (!opencvJarExists) {
                kotlin.exclude("fr/camera3d/camera/feature_edit/autoalign/**")
            }
        }
        val desktopMain = getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                // Provides Dispatchers.Main (backed by the Swing/AWT event thread) for desktop JVM;
                // without it, Dispatchers.Main throws "Module with the Main dispatcher is missing".
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                // Pure-JVM EXIF reader/writer (replaces the Android-only UnicodeExifInterface
                // CameraSync3D uses; Commons Imaging can write the UserComment tag it also needs).
                implementation("org.apache.commons:commons-imaging:1.0.0-alpha6")
                // Real VLC (libvlc) playback via the vlcj bindings - the same decode/pacing engine
                // the standalone VLC app uses, so video is hardware-decoded and frame-paced
                // properly instead of the hand-rolled software decode this app used to do (first
                // with FFmpegFrameGrabber, still choppy even after forcing hardware decoder
                // names). Requires VLC to be installed on the machine - MediaPlayerFactory()
                // locates it via vlcj's NativeDiscovery.
                implementation("uk.co.caprica:vlcj:4.11.0")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopTest = getByName("desktopTest") {
            dependencies {
                // Off-screen rendering of Composables to a bitmap (runDesktopComposeUiTest +
                // captureToImage) - used by StoreScreenshotTest to generate Microsoft Store
                // listing screenshots without launching a real window. desktopMain's
                // compose.desktop.currentOs is already on this source set's classpath (test
                // source sets extend their associated main compilation).
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "sbs3dfullscreen.resources"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "MainKt"

        // Skiko (Compose's rendering engine) loads native libraries via System.load, which JDK
        // 24+ treats as a restricted method and warns about unless native access is granted.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        // Exposes gradle.properties' appVersion (also used below as the installer's
        // packageVersion) to the running app as a system property, so AboutScreen.kt can display
        // it without a separate BuildConfig-generation step. Applies to both `run`/`runDistributable`
        // and the packaged app, same as the other jvmArgs here.
        jvmArgs += "-Dapp.version=${project.property("appVersion")}"

        // GA4 Measurement Protocol credentials, from local.properties (see above) - kept out of
        // source so the api secret isn't sitting in plain text in git history.
        jvmArgs += "-Dga.measurementId=${localProperties.getProperty("ga.measurementId", "")}"
        jvmArgs += "-Dga.apiSecret=${localProperties.getProperty("ga.apiSecret", "")}"

        // OpenCV's Java bindings load a native opencv_java*.dll at runtime (System.loadLibrary),
        // vendored alongside the jar - see libs/opencv/README.md. Only takes effect once that
        // directory actually exists, so `run`/tests work before OpenCV is vendored too.
        if (project.file("libs/opencv").exists()) {
            jvmArgs += "-Djava.library.path=${project.file("libs/opencv").absolutePath}"
        }

        // jpackage (used to build the Msi/Exe installers) needs a JDK that bundles it;
        // this points only at the packaging step, independent of the JDK running Gradle.
        javaHome = "${System.getProperty("user.home")}/.jdks/temurin-21"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = appPackageName
            packageVersion = project.property("appVersion") as String

            windows {
                shortcut = true
                menu = true
                iconFile.set(project.file("icons/icon.ico"))
            }

            fileAssociation(
                mimeType = "image/jpeg",
                extension = "jpg",
                description = "JPEG Image",
                windowsIconFile = project.file("icons/icon.ico"),
            )
            fileAssociation(
                mimeType = "image/jpeg",
                extension = "jpeg",
                description = "JPEG Image",
                windowsIconFile = project.file("icons/icon.ico"),
            )
        }
    }
}

// Compose Hot Reload's `hotRunDesktop` task (org.jetbrains.compose.hot-reload plugin) builds its
// own JavaExec from scratch and doesn't read compose.desktop.application's jvmArgs above, so the
// -Djava.library.path for vendored OpenCV needs to be repeated here for hot-reload runs too.
tasks.withType<org.jetbrains.compose.reload.gradle.ComposeHotRun>().configureEach {
    if (project.file("libs/opencv").exists()) {
        jvmArgs("-Djava.library.path=${project.file("libs/opencv").absolutePath}")
    }
}

// Compose's Windows DSL has no typed option for this, so pass the raw jpackage flag:
// it turns the "shortcut = true" / "menu = true" requests above into pre-checked,
// user-toggleable checkboxes in the MSI/EXE installer UI instead of always creating them.
tasks.withType<AbstractJPackageTask>().configureEach {
    // Only valid for installer types, not app-image (createDistributable).
    if (targetFormat == TargetFormat.Msi || targetFormat == TargetFormat.Exe) {
        freeArgs.add("--win-shortcut-prompt")
    }
}

// --- MSIX packaging (for Microsoft Store submission) -------------------------------------------
// Compose Desktop's Gradle plugin has no native MSIX target (only Msi/Exe/Deb/Rpm/AppImage), so
// this wraps the jpackage app-image (createDistributable's output, unmodified) into an MSIX by
// hand: fill packaging/msix/AppxManifest.xml's tokens in with this project's version and the
// Partner Center identity below, copy in the Store logo assets (packaging/msix/Assets - see
// tools/generate-msix-assets.ps1), then invoke makeappx.exe to pack it.
//
// One-time setup before this task can produce a submittable package:
//   1. Install the Windows 10/11 SDK (for makeappx.exe/signtool.exe):
//      https://developer.microsoft.com/windows/downloads/windows-sdk/
//   2. Reserve the app's name in Partner Center (https://partner.microsoft.com/dashboard) and
//      copy the "Package/Identity/Name" and "Package/Identity/Publisher" it gives you into
//      msixPackageIdentityName/msixPublisher below (or override via -P on the command line
//      instead of editing gradle.properties directly).
//   3. Run tools/generate-msix-assets.ps1 once to populate packaging/msix/Assets/*.png.
//
// makeappx.exe isn't normally added to PATH by the SDK installer - point msixMakeAppxPath at it
// (e.g. "C:/Program Files (x86)/Windows Kits/10/bin/<version>/x64/makeappx.exe") if it's not
// found. The resulting .msix is unsigned: Partner Center signs Store submissions itself, but a
// local sideload install (Add-AppxPackage) needs it signed with a matching test certificate first.
val msixBuildDir = layout.buildDirectory.dir("msix")

tasks.register("packageMsix") {
    group = "distribution"
    description = "Packs the Windows app-image into an MSIX for Microsoft Store submission."
    dependsOn("createDistributable")

    // Read everything from the Project/task-graph up front, at task-realization time: doLast
    // actions run under the configuration cache, which forbids capturing Project (or anything
    // holding a live reference to it, like `exec {}`) inside them - only plain values (String,
    // File) may cross that boundary.
    val version = project.property("appVersion") as String
    // MSIX requires a 4-part Major.Minor.Build.Revision version; appVersion here is 3-part.
    val msixVersion = if (version.count { it == '.' } == 2) "$version.0" else version

    val identityName = project.findProperty("msixPackageIdentityName") as? String
        ?: error("Set msixPackageIdentityName in gradle.properties (from Partner Center's app name reservation) before running packageMsix.")
    val publisher = project.findProperty("msixPublisher") as? String
        ?: error("Set msixPublisher in gradle.properties (from Partner Center's app name reservation) before running packageMsix.")
    val publisherDisplayName = project.findProperty("msixPublisherDisplayName") as? String
        ?: error("Set msixPublisherDisplayName in gradle.properties before running packageMsix.")
    val makeAppxPath = project.findProperty("msixMakeAppxPath") as? String ?: "makeappx.exe"

    val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/$appPackageName").get().asFile
    val assetsSource = project.file("packaging/msix/Assets")
    val manifestTemplateFile = project.file("packaging/msix/AppxManifest.xml")
    val stagingDir = msixBuildDir.get().dir("staging").asFile
    val outputDir = msixBuildDir.get().asFile
    val outputFile = File(outputDir, "$appPackageName-$version.msix")
    val executableToken = "app\\$appPackageName.exe"

    doLast {
        check(appImageDir.exists()) { "App-image not found at $appImageDir - createDistributable should have produced it." }
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        appImageDir.copyRecursively(File(stagingDir, "app"))

        check(assetsSource.listFiles()?.isNotEmpty() == true) {
            "No Store logo assets in $assetsSource - run tools/generate-msix-assets.ps1 first."
        }
        assetsSource.copyRecursively(File(stagingDir, "Assets"))

        val manifest = manifestTemplateFile.readText()
            .replace("{{IDENTITY_NAME}}", identityName)
            .replace("{{PUBLISHER}}", publisher)
            .replace("{{PUBLISHER_DISPLAY_NAME}}", publisherDisplayName)
            .replace("{{VERSION}}", msixVersion)
            .replace("{{EXECUTABLE}}", executableToken)
        File(stagingDir, "AppxManifest.xml").writeText(manifest)

        outputDir.mkdirs()
        val process = ProcessBuilder(makeAppxPath, "pack", "/d", stagingDir.absolutePath, "/p", outputFile.absolutePath, "/overwrite")
            .redirectErrorStream(true)
            .start()
        val processOutput = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        println(processOutput)
        check(exitCode == 0) {
            "makeappx failed with exit code $exitCode - is the Windows SDK installed and msixMakeAppxPath correct?"
        }

        logger.lifecycle("MSIX package written to ${outputFile.absolutePath}")
        logger.lifecycle("It is unsigned - sign it (signtool sign /fd SHA256 /a ...) before local sideload testing; Partner Center signs Store submissions itself.")
    }
}
