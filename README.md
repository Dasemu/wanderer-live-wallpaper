# Wanderer above the Sea of Fog — live wallpaper

An Android live wallpaper made from Caspar David Friedrich's *Wanderer above the Sea of Fog* (1818).
The painting itself is the wallpaper. OpenGL ES 3.0 shaders make its fog drift and relight the whole scene
for the time of day: blue at noon, gold and orange at sunset, moonlit at night. Sunrise and sunset come
from your coarse location, or from Madrid's hours if you don't share it.

- Kotlin, minSdk 29, no third-party runtime dependencies.
- Battery-friendly: 30 fps cap (15 optional), no rendering while hidden. In battery saver the fog stands
  still and the wallpaper redraws only every 30 s, so the light still follows the clock.
- Settings screen with a live preview, a time-of-day slider, cloud speed and frame rate.
- Lock screen: where the system doesn't allow live wallpapers there (e.g. Honor MagicOS), an option sets
  a still of the scene on the lock screen and refreshes it at each light phase of the day.

## Quick start

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # with a phone connected over adb
```

Gradle needs a full JDK 17+ (21 recommended). Open **Wanderer** from the launcher, then tap **Set as wallpaper**.

To tweak the look without a phone, open the shader preview in a browser:

```bash
python3 -m http.server 8765    # then open http://localhost:8765/tools/preview/
```

## Using your own art

See [docs/art.md](docs/art.md): which layers to export, at what size, and how to pack them.

## More

- [docs/architecture.md](docs/architecture.md): how the rendering works.
- [docs/releases.md](docs/releases.md): CI, versioning and signed releases.

## License

Code: [GNU GPL v3](LICENSE). The painting is in the public domain
(scan: Hamburger Kunsthalle, via [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Caspar_David_Friedrich_-_Wanderer_above_the_sea_of_fog.jpg)).
