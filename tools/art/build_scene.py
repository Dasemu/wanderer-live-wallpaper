#!/usr/bin/env python3
"""Build the wallpaper's scene textures.

Outputs (in app/src/main/res/drawable-nodpi/ by default):
  scene_base.webp   full painting, colour (rocks, wanderer and far mountains are read from here)
  scene_plate.webp  the painting with the foreground removed and filled with fog;
                    the shader animates this layer (fog flow) and draws the solid layers on top
  scene_mask.png    RGBA: R = mid rocks alpha, G = foreground alpha, B = sky weight, A = far mountains

Two modes:
  painting  segments Friedrich's "Wanderer above the Sea of Fog" (or a similar single painting)
  layers    packs your own separately exported layers (sky, far, fog, mid, fg)

Dev-only dependencies: opencv-python-headless, numpy (see requirements.txt).
"""
import argparse
import os

import cv2
import numpy as np

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res", "drawable-nodpi")


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def push_pull_fill(img, known):
    """Fill pixels where known == 0 with a smooth multi-scale average of the known ones."""
    img = img.astype(np.float32)
    w = known.astype(np.float32)[..., None]
    levels = []
    cur, cw = img * w, w
    while min(cur.shape[:2]) > 4:
        levels.append((cur, cw))
        cur = cv2.resize(cur, (max(1, cur.shape[1] // 2), max(1, cur.shape[0] // 2)), interpolation=cv2.INTER_AREA)
        cw = cv2.resize(cw, (cur.shape[1], cur.shape[0]), interpolation=cv2.INTER_AREA)
        if cw.ndim == 2:
            cw = cw[..., None]
    filled = cur / np.maximum(cw, 1e-6)
    for c, cwl in reversed(levels):
        up = cv2.resize(filled, (c.shape[1], c.shape[0]), interpolation=cv2.INTER_LINEAR)
        filled = np.where(cwl > 1e-3, c / np.maximum(cwl, 1e-6), up)
        filled = cwl * filled + (1 - cwl) * up
    return filled


def polygon_mask(shape, polys, scale):
    m = np.zeros(shape[:2], np.uint8)
    for p in polys:
        cv2.fillPoly(m, [(np.array(p, np.float32) * scale).astype(np.int32)], 255)
    return m


def segment_painting(img):
    """Masks for the Hamburger Kunsthalle scan (2327x2980). Polygons are in 700-px-wide preview coordinates."""
    h, w = img.shape[:2]
    k = w / 700.0
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
    L = cv2.GaussianBlur(lab[..., 0].astype(np.float32), (0, 0), 1.5 * k / 3)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    sat = cv2.GaussianBlur(hsv[..., 1].astype(np.float32), (0, 0), 2)
    ys = np.arange(h, dtype=np.float32)[:, None] / k  # preview-space y per row

    # Foreground: the dark component that touches the bottom (crag + wanderer). The sunlit top of the
    # crag is lighter, so below the wanderer's knees warm (LAB b > 133) pixels count as rock too:
    # fog and its shadows are neutral or bluish.
    b = cv2.GaussianBlur(lab[..., 2].astype(np.float32), (0, 0), 1.5)
    dark = ((L < 85) | ((b > 136) & (L < 150) & (ys > 560))).astype(np.uint8)
    dark = cv2.morphologyEx(dark, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    _, lab_cc = cv2.connectedComponents(dark, connectivity=8)
    fg_core = (lab_cc == lab_cc[h - 5, w // 2]).astype(np.uint8) * 255
    # Lit edges of the coat (teal highlights on the shoulders) are lighter than the core: grow into
    # nearby pixels that are not fog-like (fog is pale and unsaturated).
    # Only on the figure: on the crag the same rule eats shadowed fog in its crevices.
    # On the crag: darker-than-fog pixels; lower down only warm ones (shadowed fog in crevices is bluish).
    not_fog = (((L < 112) | ((sat > 80) & (L < 175))) & ((ys < 560) | ((L < 150) & ((ys < 650) | (b > 131))))).astype(np.uint8) * 255
    for _ in range(3):
        fg_core = fg_core | (cv2.dilate(fg_core, np.ones((9, 9), np.uint8)) & not_fog)
    # Hair and face are light; the walking stick is thin and mid-toned.
    head_box = polygon_mask(img.shape, [[(318, 288), (382, 288), (382, 352), (318, 352)]], k)
    head = ((sat > 70) | (L < 150)) & (head_box > 0)
    stick_box = polygon_mask(img.shape, [[(399, 470), (406, 470), (430, 604), (421, 606)]], k)
    stick = (L < 115) & (stick_box > 0)
    # Neck and white shirt collar are pale like the fog: always solid.
    neck = polygon_mask(img.shape, [[(338, 328), (362, 328), (364, 340), (372, 350), (366, 354), (326, 354), (324, 350), (332, 344), (338, 341)]], k) > 0
    fg_bin = (fg_core > 0) | head | stick | neck
    fg_bin = cv2.morphologyEx(fg_bin.astype(np.uint8) * 255, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    # The crag's top ledge is broken by pale grass and wisps: close it hard and fill enclosed holes,
    # but only below the wanderer's knees (the gap between his arm and body is real).
    low = (ys > 560).astype(np.uint8) * np.ones_like(fg_bin)
    closed = cv2.morphologyEx(fg_bin, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9)))
    outside = np.zeros((h + 2, w + 2), np.uint8)
    flood = closed.copy()
    cv2.floodFill(flood, outside, (0, 0), 255)
    holes_in = cv2.bitwise_not(flood)
    n_h, lab_h, stats, _ = cv2.connectedComponentsWithStats(holes_in, connectivity=4)
    small = np.isin(lab_h, [i for i in range(1, n_h) if stats[i, cv2.CC_STAT_AREA] < 60 * k * k])
    filled = closed | (small.astype(np.uint8) * 255)
    # Where the trousers touch dark things (fog shadows between the legs, a tree behind the left shin)
    # only the very dark trousers count as foreground.
    legs_gap = polygon_mask(img.shape, [
        [(300, 530), (342, 530), (348, 614), (296, 614)],
        [(262, 488), (287, 488), (285, 600), (262, 600)],
    ], k)
    fg_bin = np.where((low > 0) & (legs_gap == 0), filled, fg_bin)
    # Soft edge: fade by luminance inside a slightly grown region, so painted fog over the rim stays fog.
    grown = cv2.dilate(fg_bin, np.ones((7, 7), np.uint8))
    fg = np.maximum((fg_bin > 0) * 1.0 * np.maximum(smoothstep(175, 95, L), (b > 136) * (L < 150) * (ys > 560)), 0) * (grown > 0)
    fg = np.maximum(fg, cv2.erode(fg_bin, np.ones((9, 9), np.uint8)) / 255.0)
    fg = np.where(ys > 560, np.maximum(fg, cv2.erode(fg_bin, np.ones((3, 3), np.uint8)) / 255.0), fg)
    fg = np.maximum(fg, head * 1.0)
    fg = np.maximum(fg, cv2.GaussianBlur(neck.astype(np.float32), (0, 0), 1.0))
    # Friedrich painted a pale rim of fog hugging the coat; inside the mask it would keep its original
    # colour while the fog around it is relit, which reads as a halo. Cut pale pixels near the edge.
    zone = (fg_bin > 0) & (cv2.erode(fg_bin, np.ones((9, 9), np.uint8)) == 0)
    pale = smoothstep(120, 160, L) * (1 - smoothstep(50, 90, sat))
    keep = head | stick | neck
    fg = np.where(zone & ~keep, fg * (1 - pale), fg)
    # Between the legs only the dark trousers are solid; grey fog there must stay see-through.
    # (the shadowed fog there is mid-grey, L ~95-125; the trousers are ~50)
    fg = np.where(legs_gap > 0, fg * smoothstep(88, 66, L), fg)
    # The trousers have a thin lit rim painted along their edge: keep a couple of pixels around the
    # dark core, or that rim is lost and the fog behind reads as a halo.
    core = ((L < 80) & (legs_gap > 0)).astype(np.uint8)
    n_c, lab_c, st_c, _ = cv2.connectedComponentsWithStats(core, connectivity=8)
    core = np.isin(lab_c, [i for i in range(1, n_c) if st_c[i, cv2.CC_STAT_AREA] > 400 * k * k]).astype(np.uint8)
    rim = cv2.dilate(core, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))) & (legs_gap > 0)
    fg = np.where(rim > 0, np.maximum(fg, cv2.GaussianBlur(rim.astype(np.float32), (0, 0), 1.0)), fg)
    # Pale, cool pixels on the crag are wisps of fog lying on the rock, not rock.
    crag_wisps = smoothstep(135, 160, L) * (1 - smoothstep(40, 70, sat)) * (b < 134) * (ys > 580)
    fg = fg * (1 - crag_wisps)
    # ...except one cool, pale lump of rock at the top-left of the ledge.
    lump = polygon_mask(img.shape, [[(172, 603), (180, 599), (206, 599), (209, 606), (207, 614), (171, 617)]], k)
    fg = np.maximum(fg, (lump > 0) * smoothstep(168, 146, L))
    fg = cv2.GaussianBlur(fg.astype(np.float32), (0, 0), 0.8)

    # Mid rocks and trees: darker than the fog, inside two hand-drawn hulls.
    hulls = polygon_mask(img.shape, [
        [(0, 395), (120, 395), (215, 445), (300, 470), (310, 625), (0, 625)],
        [(405, 425), (700, 425), (700, 560), (405, 560)],
    ], k)
    # Let the mid layer run a few pixels under the foreground: the figure is drawn on top, and a gap
    # between the two would show the smooth fill as a light seam.
    fg_zone = cv2.erode((fg > 0.5).astype(np.uint8), np.ones((9, 9), np.uint8))
    mid = smoothstep(178, 112, L) * (hulls > 0) * (1 - fg_zone)
    mid = cv2.GaussianBlur(mid.astype(np.float32), (0, 0), 1.0)

    # Far mountains (incl. the rock pinnacle on the right): mid-toned, in the horizon band.
    band = smoothstep(228, 250, ys) * smoothstep(432, 405, ys)
    far = smoothstep(206, 168, L) * band * (1 - fg) * (1 - mid)
    far = cv2.GaussianBlur(far.astype(np.float32), (0, 0), 3)

    # Sky weight: the painted sky above the mountains (relit as sky rather than as fog).
    sky = smoothstep(395, 300, ys) * np.ones_like(L) * (1 - far) * (1 - fg)
    sky = cv2.GaussianBlur(sky.astype(np.float32), (0, 0), 6)
    return mid, fg, sky, far


def build_plate(base, mid, fg):
    # Grow the holes just enough to swallow the soft edges; a wider ring of smooth fill would read
    # as a halo around the wanderer.
    # Only the foreground is cut out (it moves with its own parallax). The mid rocks stay painted in the
    # plate: they share its parallax, the fog does not flow next to them, and any translucent rock
    # edge then shows the same rock underneath instead of a light seam of filled-in fog.
    holes = fg > 0.05
    holes = cv2.dilate(holes.astype(np.uint8), np.ones((7, 7), np.uint8)) > 0
    known = (~holes).astype(np.float32)
    filled = push_pull_fill(base, known)
    # Narrow gaps (between the legs, around the stick, the ring around every silhouette) are visible,
    # so continue the painted fog texture there with inpainting; the smooth fill only remains deep
    # inside large holes, which the solid layers always cover.
    dist = cv2.distanceTransform(holes.astype(np.uint8), cv2.DIST_L2, 5)
    near = (holes & (dist < 40)).astype(np.uint8)
    work = base.astype(np.uint8).copy()
    work[holes & ~(near > 0)] = filled[holes & ~(near > 0)].astype(np.uint8)
    inpainted = cv2.inpaint(work, near, 9, cv2.INPAINT_TELEA).astype(np.float32)
    filled = np.where(near[..., None] > 0, inpainted, filled)
    # Feather the seam a little so the fill blends into the painted fog.
    soft = cv2.GaussianBlur(known, (0, 0), 2)[..., None]
    plate = base * soft + filled * (1 - soft)
    return np.clip(plate, 0, 255).astype(np.uint8)


def load_rgba(path, size=None):
    im = cv2.imread(path, cv2.IMREAD_UNCHANGED)
    if im is None:
        raise SystemExit(f"cannot read {path}")
    if im.ndim == 2:
        im = cv2.cvtColor(im, cv2.COLOR_GRAY2BGRA)
    if im.shape[2] == 3:
        im = cv2.cvtColor(im, cv2.COLOR_BGR2BGRA)
    if size and (im.shape[1], im.shape[0]) != size:
        im = cv2.resize(im, size, interpolation=cv2.INTER_AREA)
    return im.astype(np.float32) / 255.0


def over(dst_rgb, src):
    a = src[..., 3:4]
    return src[..., :3] * a + dst_rgb * (1 - a)


def pack_layers(args):
    sky = load_rgba(args.sky)
    size = (sky.shape[1], sky.shape[0])
    far = load_rgba(args.far, size) if args.far else np.zeros_like(sky)
    fog = load_rgba(args.fog, size) if args.fog else np.zeros_like(sky)
    mid = load_rgba(args.mid, size) if args.mid else np.zeros_like(sky)
    fg = load_rgba(args.fg, size)
    plate = over(over(sky[..., :3], far), fog)
    base = over(over(plate, mid), fg)
    sky_w = (1 - far[..., 3]) * (1 - fog[..., 3])
    far_w = far[..., 3] * (1 - fog[..., 3])
    return (base * 255).astype(np.uint8), (plate * 255).astype(np.uint8), mid[..., 3], fg[..., 3], sky_w, far_w


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="mode", required=True)
    p = sub.add_parser("painting")
    p.add_argument("image")
    p.add_argument("--max-width", type=int, default=2327)
    p.add_argument("--trim", type=float, default=0.012, help="fraction cropped from each edge (scan borders)")
    lay = sub.add_parser("layers")
    lay.add_argument("--sky", required=True, help="opaque sky, defines the canvas size")
    lay.add_argument("--far", help="far mountains, transparent PNG")
    lay.add_argument("--fog", help="painted fog/clouds between far and mid, transparent PNG")
    lay.add_argument("--mid", help="mid rocks, transparent PNG")
    lay.add_argument("--fg", required=True, help="foreground (figure + rock), transparent PNG")
    for sp in (p, lay):
        sp.add_argument("--out", default=OUT_DIR)
        sp.add_argument("--debug", help="also write a preview sheet to this path")
    args = ap.parse_args()

    if args.mode == "painting":
        base = cv2.imread(args.image)
        if base.shape[1] > args.max_width:
            s = args.max_width / base.shape[1]
            base = cv2.resize(base, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
        # Segment the whole scan (the hand-drawn polygons assume it), then crop its borders.
        mid, fg, sky, far = segment_painting(base)
        plate = build_plate(base.astype(np.float32), mid, fg)
        th, tw = int(base.shape[0] * args.trim), int(base.shape[1] * args.trim)
        crop = (slice(th, base.shape[0] - th), slice(tw, base.shape[1] - tw))
        base, plate, mid, fg, sky, far = (a[crop] for a in (base, plate, mid, fg, sky, far))
    else:
        base, plate, mid, fg, sky, far = pack_layers(args)

    os.makedirs(args.out, exist_ok=True)
    mask = np.dstack([sky, fg, mid, far])  # OpenCV writes BGRA, so this is R=mid G=fg B=sky A=far
    mask = (np.clip(mask, 0, 1) * 255).astype(np.uint8)
    cv2.imwrite(os.path.join(args.out, "scene_base.webp"), base, [cv2.IMWRITE_WEBP_QUALITY, 92])
    cv2.imwrite(os.path.join(args.out, "scene_plate.webp"), plate, [cv2.IMWRITE_WEBP_QUALITY, 90])
    # Masks are soft, so 1600 px wide is plenty and keeps GPU memory down (the app decodes them unscaled).
    if mask.shape[1] > 1600:
        mask = cv2.resize(mask, (1600, round(mask.shape[0] * 1600 / mask.shape[1])), interpolation=cv2.INTER_AREA)
    cv2.imwrite(os.path.join(args.out, "scene_mask.png"), mask, [cv2.IMWRITE_PNG_COMPRESSION, 9])

    if args.debug:
        def small(x):
            x = x if x.ndim == 3 else cv2.cvtColor((np.clip(x, 0, 1) * 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
            return cv2.resize(x, (350, int(350 * x.shape[0] / x.shape[1])), interpolation=cv2.INTER_AREA)
        sheet = np.hstack([small(base), small(plate), small(mid), small(fg), small(sky), small(far)])
        cv2.imwrite(args.debug, sheet)
    # skyRef for assets/scene.json: mean linear luminance of the sky in the plate.
    lin = (plate[..., ::-1].astype(np.float32) / 255.0) ** 2.2 @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    sky_ref = float((lin * sky).sum() / max(sky.sum(), 1e-6))
    print(f"wrote scene textures ({base.shape[1]}x{base.shape[0]}) to {os.path.abspath(args.out)}")
    print(f'set "skyRef": {sky_ref:.3f} in app/src/main/assets/scene.json')


if __name__ == "__main__":
    main()
