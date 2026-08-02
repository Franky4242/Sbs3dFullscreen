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
- Sync common files coming from my Android app Camera 3D : `./tools/sync-from-android.ps1`
- Build Windows installers (MSI/EXE): `./gradlew.bat packageMsi` / `./gradlew.bat packageExe`
  - Requires a JDK that bundles jpackage at `~/.jdks/temurin-21` (set via `javaHome` in `build.gradle.kts`), independent 
  of whatever JDK runs Gradle itself.
  - Bump `appVersion` in `gradle.properties` before every release build — jpackage/Windows Installer only auto-uninstalls 
  the previous version in-place when this number increases; an unchanged version forces users into Add/Remove Programs instead.

## Architecture

WARNING : some files are sync from the Android App fr.camera3d.camera : these files are stored in sub directories 
starting by fr.camera3d.camera. Never modify them. If you need to modify them you must alert me and if I agree, you 
should modify the Android App original files in `c/Documents/AndroidStudioProjects/CameraSync3D/`. 

Windows app files (all in the default package under `src/desktopMain/kotlin/`, no `src/`-relative imports needed between them):

- `Main.kt`: entry point and the `Window`/`WindowState` shell only — window chrome, focus, key handling, and locale recomposition boundaries. Delegates all screen/navigation state to `AppViewModel`.
- `AppViewModel.kt`: plain (non-Composable) state holder — owns `screen` (`Screen.Welcome`/`Screen.ImageView`), `imageFiles`, `currentImageIndex`, `language`, and the logic to mutate them (`onFilesChosen`, `showNextImage`/`showPreviousImage`, `closeImageView`, `onLanguageChosen`). Backed by `mutableStateOf`, so it's read directly from Composables, but it isn't itself `@Composable` and holds no `Window`/AWT references — window-placement side effects (`WindowState.placement`) stay in `Main.kt`, triggered from the same callbacks that call into the view model.
- `WelcomeScreen.kt`: language picker (EN/FR) + a native `java.awt.FileDialog` (multi-select, JPEG-filtered) to choose images.
- `ImageScreen.kt`: renders the current image full-bleed on black.
- `LocalAppLocale.kt`: the locale-override composition local (see below).

## Stereo (SBS) display and depth-shift overlays

- The images this app opens are **full-width side-by-side 3D**: a single JPEG whose left half is the left-eye photo and whose right half is the right-eye photo (see the "SBS image width types" note in memory — the app does not handle half-width SBS, so there's no stretch/split logic anywhere). Because both eye-halves already live in the one bitmap, `ImageScreen.kt` just draws it once, full-bleed (`Modifier.fillMaxSize()` + `ContentScale.Fit`) — the left/right stereo split falls out of the source image for free, with no per-half rendering needed for the photo itself.
- Anything drawn **on top** of the photo (titles, comments, the EXIF info HUD, …) is different: a single overlay positioned once would sit at the same pixel position in both eye-halves, which reads as pinned flat to the screen glass in 3D rather than floating at a chosen depth. The fix used everywhere in this codebase is to duplicate the overlay once per half (inside a `Row` of two equal-`weight(1f)` boxes spanning `fillMaxSize()`) and shift each copy horizontally in *opposite* directions by a `shiftPercent` of half the screen width:
  ```
  val shift = halfWidth * shiftPercent
  // left half copy:  offset by -shift / 2
  // right half copy: offset by +shift / 2
  ```
  Sign convention: **positive `shiftPercent` pushes the overlay farther behind the screen; negative brings it out toward the viewer.** Typical values are small, -3%..3% (see the `playlist_*_z_documentation` strings). This is the same disparity trick the eyes use to perceive depth — shifting the two copies apart (or together) changes where the brain reconstructs the overlay in Z.
- Reference implementations of this pattern: `PortableSlideshowSlides.kt`'s `ComposablePortableTitleSlide` (title/subtitle, driven by `Playlist.titleZPercent`/`subtitleZPercent`) and `ComposablePortableEndSlide` (animated `zShiftPercent`); `PlaylistItem.commentZPercent` for a photo's comment overlay; and `Exif3dInfoPanel.kt`'s `InfoPanelShiftPercent` (a fixed -1%, so the Shift/Ctrl-held EXIF HUD reads as floating just in front of the screen).

## Other notes:

- Window chrome is coupled to screen state: `undecorated` is true only in `ImageView`, and since AWT can't toggle `undecorated`
after the peer is created, the whole `Window` is wrapped in `key(undecorated)` to force recreation when it changes.
- Fullscreen is implemented as **undecorated + `WindowPlacement.Maximized`**, not `WindowPlacement.Fullscreen` — real OS exclusive fullscreen gets auto-minimized by Windows when the window loses focus (e.g. when a 3D monitor's own "activate 3D" popup steals focus), whereas Maximized just deactivates. See the comment in `Main.kt`'s `onFilesChosen` callback.
- Multi-image navigation (when multiple files are chosen) happens in `Main.kt` via `onPreviewKeyEvent` on the focused root `Box`, which calls into `AppViewModel`: Escape calls `closeImageView()`, Space/Right calls `showNextImage()`, Left calls `showPreviousImage()`.
- Launch-via-file-association: `main(args)` treats `args.firstOrNull()` as a file path; if present, `AppViewModel` is constructed already in `ImageView` with that file. This is what Windows passes when the app is invoked through the registered `.jpg`/`.jpeg` file association (configured in `build.gradle.kts` via `fileAssociation(...)`).
- Localization: uses Compose Multiplatform's `stringResource`/`compose.components.resources` (strings in `src/commonMain/composeResources/values{,-fr}/strings.xml`). Since Compose's resource system has no public API to override locale per-composition (only `Locale.getDefault()`), `LocalAppLocale` mutates the JVM default `Locale` on language switch and forces recomposition via `key(language)` in `Main.kt`.
- `commonMain`/`desktopMain` source set split exists for future KMP targets, but only `desktop` (JVM) is configured as a target; there's no Android/iOS/web target today.
- when copying strings from the Camera 3D Android app, don't forget to unescape quotes (escaping quotes is required for Android and is not needed for this Kotlin app) 
- when building UI create round icons and not rounded corner icons. And add a background the the icon
- for each prompt I give to you, start by rewording it in idiomatic English to help me improve my English and then execute the prompt