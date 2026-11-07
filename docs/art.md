# Art: layers, sizes and packing

The app renders three textures from `app/src/main/res/drawable-nodpi/`:

| File | Content |
|---|---|
| `scene_base.webp` | The full image, in colour. Rocks, the figure and the far mountains are read from here. |
| `scene_plate.webp` | The image without the foreground, with fog filled in behind it. The shader makes its fog flow, except next to rocks and the figure. |
| `scene_mask.png` | RGBA masks. R = mid rocks, G = foreground, B = sky, A = far mountains. Anything else in the plate counts as fog. |

`tools/art/build_scene.py` builds all three. Do not edit them by hand.

## Paint for neutral daylight

The shader relights everything for the time of day:

- The sky becomes a time-of-day gradient that keeps your clouds as light and shade.
- Fog keeps its values but takes the fog colours of the hour.
- Solid layers are multiplied by the ambient light and get a rim light toward the sun or moon.

So paint everything under soft, even, overcast daylight, with no sunset colours and no strong shadows.
The time-of-day palettes live in `app/src/main/assets/palettes.json`.

## Export sizes

Export every layer at the **same canvas size, aligned pixel for pixel**:

- **Recommended: 1800 × 3200 px (9:16).** On a 20:9 phone this fills the screen with room for parallax.
- Minimum width: 1600 px. The app shows about 80% of the width and scrolls the rest with the home screen.
- A shorter canvas is fine (the painting is about 4:5). The app continues the sky upward from the top
  rows of your sky layer, so keep the top 20% of the sky calm and free of distinct shapes.
- Keep important content (the figure) inside the central 70% of the width.

## Layers to export

| Layer | Format | Content |
|---|---|---|
| `sky.png` | opaque | Sky only, with clouds. It defines the canvas size. |
| `far.png` | transparent | Distant mountains (optional). |
| `fog.png` | transparent | Painted fog or sea of clouds between the far mountains and the mid rocks (optional; recommended). |
| `mid.png` | transparent | Mid-distance rocks and trees (optional). |
| `fg.png` | transparent | The figure and the rock they stand on. |

Use straight (non-premultiplied) alpha with clean edges, and no fog baked into the edges of `mid` or `fg`:
the app adds fog in front of them.

## Packing

```bash
python3 -m venv .venv && .venv/bin/pip install -r tools/art/requirements.txt
.venv/bin/python tools/art/build_scene.py layers --sky sky.png --far far.png --fog fog.png --mid mid.png --fg fg.png --debug sheet.jpg
```

The script prints a `skyRef` value. Put it into `app/src/main/assets/scene.json` and set `horizon` there too:
the row where the far fog meets the sky, as a fraction of the canvas height from the top (0.44 for the painting).
Then check the result in `tools/preview/` and rebuild the APK.

The painting itself is rebuilt with `build_scene.py painting art/source/wanderer_original.jpg`. That
segmentation uses hand-tuned polygons for this particular scan.
