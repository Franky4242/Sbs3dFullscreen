import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "MainKt"

        // jpackage (used to build the Msi/Exe installers) needs a JDK that bundles it;
        // this points only at the packaging step, independent of the JDK running Gradle.
        javaHome = "${System.getProperty("user.home")}/.jdks/temurin-21"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "sbs3Dfullscreen"
            packageVersion = project.property("appVersion") as String

            windows {
                shortcut = true
                menu = true
                iconFile.set(project.file("icons/icon.ico"))
            }
        }
    }
}

// Compose's Windows DSL has no typed option for this, so pass the raw jpackage flag:
// it turns the "shortcut = true" / "menu = true" requests above into pre-checked,
// user-toggleable checkboxes in the MSI/EXE installer UI instead of always creating them.
tasks.withType<AbstractJPackageTask>().configureEach {
    freeArgs.add("--win-shortcut-prompt")
}
