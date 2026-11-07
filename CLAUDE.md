# CLAUDE.md

## Overview
Android live wallpaper (Kotlin, minSdk 29) inspired by Friedrich's "Wanderer above the Sea of Fog".
OpenGL ES 3.0 shaders render sky, sun/moon, stars and fog; the foreground is layered PNG art.

## Commands
- Build debug APK: `./gradlew assembleDebug` (output: `app/build/outputs/apk/debug/`)
- Test: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lintDebug`
- Install on device: `./gradlew installDebug`

## Architecture
See `docs/architecture.md`.

## Conventions
- Version lives in the root `VERSION` file (bumped by CI only).

## Workflow quirks
- Local JDK is 25: Gradle must be 9.1+ to run on it. CI uses JDK 21.

## Deployment
Not distributed through a store yet; the debug APK is the deliverable.

## Gotchas
