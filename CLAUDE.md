# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`sbs3Dfullscreen` is a Windows desktop JPEG viewer built with Kotlin Multiplatform + Compose Desktop. It's designed to 
open one or several JPEG (typically a side-by-side 3D image) in a borderless, maximized window suitable for a s
tereoscopic 3D monitor, and to be launched directly via Windows file association (double-click a `.jpg`/`.jpeg`).

## Commands

- Run in dev mode: `./gradlew.bat run`
- Run the packaged distributable: `./gradlew.bat runDistributable` (or `runRelease` / `runReleaseDistributable`)
- Run tests: `./gradlew.bat desktopTest` (there is a `commonTest` source set wired up via `kotlin("test")`, but no tests 
exist yet)
- Build Windows installers (MSI/EXE): `./gradlew.bat packageMsi` / `./gradlew.bat packageExe`
  - Requires a JDK that bundles jpackage at `~/.jdks/temurin-21` (set via `javaHome` in `build.gradle.kts`), independent 
  of whatever JDK runs Gradle itself.
  - Bump `appVersion` in `gradle.properties` before every release build — jpackage/Windows Installer only auto-uninstalls 
  the previous version in-place when this number increases; an unchanged version forces users into Add/Remove Programs instead.

## Architecture

Files (all in the default package under `src/desktopMain/kotlin/`, no `src/`-relative imports needed between them):

- `Main.kt`: entry point and the `Window`/`WindowState` shell only — window chrome, focus, key handling, and locale recomposition boundaries. Delegates all screen/navigation state to `AppViewModel`.
- `AppViewModel.kt`: plain (non-Composable) state holder — owns `screen` (`Screen.Welcome`/`Screen.ImageView`), `imageFiles`, `currentImageIndex`, `language`, and the logic to mutate them (`onFilesChosen`, `showNextImage`/`showPreviousImage`, `closeImageView`, `onLanguageChosen`). Backed by `mutableStateOf`, so it's read directly from Composables, but it isn't itself `@Composable` and holds no `Window`/AWT references — window-placement side effects (`WindowState.placement`) stay in `Main.kt`, triggered from the same callbacks that call into the view model.
- `WelcomeScreen.kt`: language picker (EN/FR) + a native `java.awt.FileDialog` (multi-select, JPEG-filtered) to choose images.
- `ImageScreen.kt`: renders the current image full-bleed on black.
- `LocalAppLocale.kt`: the locale-override composition local (see below).

Other notes:

- Window chrome is coupled to screen state: `undecorated` is true only in `ImageView`, and since AWT can't toggle `undecorated`
after the peer is created, the whole `Window` is wrapped in `key(undecorated)` to force recreation when it changes.
- Fullscreen is implemented as **undecorated + `WindowPlacement.Maximized`**, not `WindowPlacement.Fullscreen` — real OS exclusive fullscreen gets auto-minimized by Windows when the window loses focus (e.g. when a 3D monitor's own "activate 3D" popup steals focus), whereas Maximized just deactivates. See the comment in `Main.kt`'s `onFilesChosen` callback.
- Multi-image navigation (when multiple files are chosen) happens in `Main.kt` via `onPreviewKeyEvent` on the focused root `Box`, which calls into `AppViewModel`: Escape calls `closeImageView()`, Space/Right calls `showNextImage()`, Left calls `showPreviousImage()`.
- Launch-via-file-association: `main(args)` treats `args.firstOrNull()` as a file path; if present, `AppViewModel` is constructed already in `ImageView` with that file. This is what Windows passes when the app is invoked through the registered `.jpg`/`.jpeg` file association (configured in `build.gradle.kts` via `fileAssociation(...)`).
- Localization: uses Compose Multiplatform's `stringResource`/`compose.components.resources` (strings in `src/commonMain/composeResources/values{,-fr}/strings.xml`). Since Compose's resource system has no public API to override locale per-composition (only `Locale.getDefault()`), `LocalAppLocale` mutates the JVM default `Locale` on language switch and forces recomposition via `key(language)` in `Main.kt`.
- `commonMain`/`desktopMain` source set split exists for future KMP targets, but only `desktop` (JVM) is configured as a target; there's no Android/iOS/web target today.

