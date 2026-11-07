# CLAUDE.md

## Overview
Android live wallpaper (Kotlin, minSdk 29) that animates and relights Friedrich's "Wanderer above the Sea of Fog".
The painting is split into textures (base, fog plate, masks); OpenGL ES 3.0 shaders flow the fog and relight by time of day.

## Commands
- Build debug APK: `./gradlew assembleDebug` (output: `app/build/outputs/apk/debug/`)
- Test: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lintDebug`
- Shader preview (no phone): `python3 -m http.server 8765`, then open `http://localhost:8765/tools/preview/` (`?sheet=480,1080` renders a contact sheet)
- Rebuild scene textures: `python tools/art/build_scene.py painting art/source/wanderer_original.jpg` (needs `tools/art/requirements.txt`)

## Architecture
See `docs/architecture.md` (rendering, time of day, threads) and `docs/art.md` (layers, sizes).

## Conventions
- Version lives in the root `VERSION` file and is bumped by CI only.
- Shaders are GLSL ES 3.00 shared verbatim by the app and `tools/preview`; keep both working.
- Tunables live in `assets/scene.json` and `assets/palettes.json`, not in code.

## Workflow quirks
- The system `java` here is a JRE without javac: run Gradle with `JAVA_HOME=$HOME/Android/jdk21` (the Stop hook does this).
- Gradle 9.9 + AGP 9.5 (built-in Kotlin, no kotlin-android plugin).
- After changing the masks or the plate, check several hours in the preview, especially edges around the wanderer.

## Deployment
GitHub Releases: every version on `main` gets a signed release APK (see `docs/releases.md` for the signing secrets).

## Gotchas
- `scene_mask.png` must be decoded unpremultiplied (alpha = far-mountain mask, not coverage).
- The mask segmentation polygons in `build_scene.py` are in 700-px-wide preview coordinates of the untrimmed scan.
