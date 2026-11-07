# Architecture

## Rendering (two passes per frame)

1. `assets/shaders/fog.frag` draws procedural fog densities into an RGBA8 FBO at 1/3 of the screen size
   (1/5 and 3 noise octaves in battery saver): haze over the far mountains, a fog sea that covers the mid
   rocks' bases, wisps around the crag, and a noise channel that de-syncs the flow map.
2. `assets/shaders/scene.frag` is one full-screen triangle. Back to front, it draws:
   - the plate (sky, mountains, mid rocks and painted fog), relit per region, with the fog moved by a two-phase flow map that stays still next to rocks and the figure;
   - the sun, the moon (with its real phase) and the stars;
   - the procedural haze;
   - the mid rocks;
   - the fog sea;
   - the wanderer with a rim light;
   - the wisps.

   It ends with a vignette, a soft highlight shoulder, gamma and dithering.

Screen uv maps to painting uv through `LayerMapping`. The painting pans as one piece with the home screen,
because a single painting has nothing behind the wanderer to reveal. Depth comes from the procedural fog
instead: the fog sea and the wisps pan a little further (`parallax.fogSea` / `fogWisps` in `scene.json`).
With a single home page (offset step 0 or >= 1), the painting stays centred. On portrait screens the sky
extends above the painting.

## Time of day

`sky/Solar.kt` (NOAA algorithm) gives sunrise, solar noon, sunset, the sun's elevation and the moon's phase.
`sky/SkyModel.kt` places the keyframes of `palettes.json` around those events and blends them with a
smoothstep. It re-evaluates once per second, not every frame. Places:

- Fixed mode: Madrid, in the Europe/Madrid time zone.
- Location mode: the last coarse fix (rounded to about 1 km) in the device's time zone.

## Threads and battery

`render/RenderLoop.kt` owns a GL thread with EGL. Frames come from `Choreographer.postFrameCallbackDelayed`,
so the thread sleeps between frames at the fps cap instead of waking on every vsync. In battery saver all
motion freezes and a frame is drawn every 30 s (only the time-of-day light changes); settings or power-mode
changes force an immediate frame. Frames stop while the
wallpaper is hidden. Settings flow through SharedPreferences into `SceneController`, which the GL thread
reads each frame.

## Lock screen stills

`lock/LockScreenUpdater` renders one frame off screen (EGL pbuffer, same `SceneRenderer`) at the screen's
size and sets it with `WallpaperManager.setBitmap(..., FLAG_LOCK)`. It reschedules itself with an inexact
`AlarmManager.setWindow` for the next light phase (`SkyModel.keyframeInstants`): about 8 renders a day.
Boot, clock and time-zone changes reschedule it (`LockScreenReceiver`).

## Dev preview

`tools/preview/index.html` runs the same GLSL in WebGL2, with a time slider. Add `?sheet=480,780,1080`
to get a contact sheet of several hours.
