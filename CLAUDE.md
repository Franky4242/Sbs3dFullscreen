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

- use ViewModel/state-management layer 
- Two screens, driven by a `Screen` enum (`Welcome`, `ImageView`), swapped in a `when` inside a single `Window`:
  - `WelcomeScreen`: language picker (EN/FR) + a native `java.awt.FileDialog` (multi-select, JPEG-filtered) to choose images.
  - `ImageScreen`: renders the list of images full-bleed on black.
- Window chrome is coupled to screen state: `undecorated` is true only in `ImageView`, and since AWT can't toggle `undecorated`
after the peer is created, the whole `Window` is wrapped in `key(undecorated)` to force recreation when it changes.

