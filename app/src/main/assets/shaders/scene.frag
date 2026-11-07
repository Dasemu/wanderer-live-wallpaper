#version 300 es
// Full-resolution composite. Back to front: painted sky + far mountains + painted fog (the "plate",
// relit per region and with the fog flowing), sun/moon/stars, procedural haze, mid rocks,
// procedural fog sea, the wanderer on his crag, and thin wisps in front.
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uBase;   // full painting
uniform sampler2D uPlate;  // painting without mid rocks / foreground
uniform sampler2D uMask;   // R mid rocks, G foreground, B sky, A far mountains
uniform sampler2D uFog;    // low-res procedural fog (see fog.frag)

uniform vec4 uRectPlate;   // screen -> painting uv per parallax layer (xy scale, zw offset)
uniform vec4 uRectMid;
uniform vec4 uRectFg;

uniform vec3 uSkyTop;
uniform vec3 uSkyHorizon;
uniform vec3 uSun;
uniform vec3 uAmbient;
uniform vec3 uFogLit;
uniform vec3 uFogShadow;
uniform vec3 uLightColor;
uniform float uGlow;
uniform float uStars;
uniform float uHaze;

uniform vec2 uSunPos;      // screen uv, y up
uniform vec2 uMoonPos;
uniform vec2 uLightPos;
uniform float uSunVis;
uniform float uMoonVis;
uniform float uMoonPhase;

uniform float uScreenAspect;   // width / height
uniform float uPaintAspect;    // painting width / height
uniform float uScreenHorizon;  // screen uv y of the painting's horizon
uniform float uHorizon;        // painting v of the horizon (0 = top)
uniform float uSkyRef;         // mean linear luminance of the painted sky
uniform float uTime;           // seconds, wrapped
uniform float uFlowTime;       // flow cycles, wrapped
uniform float uFlowFog;        // flow distance per cycle (painting uv)
uniform float uFlowSky;
uniform float uZoom;           // launcher zoom-out, 0..1

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 toLinear(vec3 c) { return pow(c, vec3(2.2)); }

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

vec2 paintUv(vec2 uv, vec4 r) { return vec2(uv.x, 1.0 - uv.y) * r.xy + r.zw; }

// Above the top of the painting, continue the sky with its upper clouds, stretched and sheared so
// no mirror symmetry shows; detail is faded out further up (see aboveFade).
vec2 extendSky(vec2 p) {
    if (p.y < 0.0) {
        float e = -p.y;
        float x = p.x + e * 0.35;
        p = vec2(1.0 - abs(1.0 - mod(x, 2.0)), 0.004 + e * 0.45);
    }
    return p;
}

// One star per lit cell; two grids (fine faint stars + sparse bright ones) give a fuller sky.
float starLayer(vec2 p, float density, float sizeMin, float sizeMax, float gain) {
    vec2 cell = floor(p);
    float h = hash(cell);
    if (h > density) return 0.0;
    vec2 pos = vec2(hash(cell + 1.7), hash(cell + 9.2)) * 0.7 + 0.15;
    float size = mix(sizeMin, sizeMax, pow(hash(cell + 4.1), 4.0));
    float d = length(fract(p) - pos);
    // Whole-number rates (rad/s) so wrapping uTime at 200π never makes the twinkle jump.
    float twinkle = 0.6 + 0.4 * sin(uTime * floor(1.0 + 3.0 * hash(cell + 3.3)) + h * 80.0);
    float bright = 0.3 + 0.7 * hash(cell + 6.6);
    return smoothstep(size, 0.0, d) * twinkle * bright * gain;
}

float stars(vec2 uv) {
    vec2 p = uv * vec2(uScreenAspect, 1.0);
    return starLayer(p * 150.0, 0.35, 0.05, 0.12, 0.55)
         + starLayer(p * 55.0 + 17.0, 0.18, 0.05, 0.13, 1.3);
}

vec3 moon(vec2 uv, out float disk) {
    const float R = 0.024;
    vec2 m = (uv - uMoonPos) * vec2(uScreenAspect, 1.0) / R;
    float r = length(m);
    disk = smoothstep(1.0, 0.9, r);
    float k = cos(uMoonPhase * 6.2831853);
    float tx = m.x / max(sqrt(max(1.0 - m.y * m.y, 0.0)), 1e-3);
    float lit = uMoonPhase < 0.5
        ? smoothstep(k - 0.12, k + 0.12, tx)
        : 1.0 - smoothstep(-k - 0.12, -k + 0.12, tx);
    float maria = 1.0 - 0.22 * smoothstep(0.45, 0.75, 0.65 * vnoise(m * 1.8 + 7.0) + 0.35 * vnoise(m * 4.0 + 3.0));
    return vec3(1.0, 0.97, 0.9) * (lit * maria * 1.3 + 0.03);
}

// Shade a fog density using a second sample shifted toward the light: edges facing it glow.
vec3 shadeFog(float d, float dToLight, float depth, float scatter) {
    float light = clamp(0.62 + (d - dToLight) * 2.5, 0.0, 1.0);
    vec3 c = mix(uFogShadow, uFogLit, light);
    c = mix(c, uSkyHorizon, (1.0 - depth) * 0.3);
    return c + uLightColor * scatter * 0.5;
}

// Pale, unsaturated pixels on the edge of a solid layer are painted fog, not rock: fade them out
// so the relit fog plate shows through instead of a halo in the painting's original colours.
float solidAlpha(float a, vec3 c, float lo, float hi) {
    float l = dot(c, LUMA);
    float mx = max(c.r, max(c.g, c.b));
    float sat = (mx - min(c.r, min(c.g, c.b))) / max(mx, 1e-3);
    float edge = 1.0 - smoothstep(0.6, 0.95, a);  // only the soft rim, never the solid interior
    return a * (1.0 - smoothstep(lo, hi, l) * (1.0 - smoothstep(0.2, 0.45, sat)) * edge);
}

void main() {
    vec2 uv = 0.5 + (vUv - 0.5) * (1.0 - 0.04 * uZoom);
    vec2 aspectV = vec2(uScreenAspect, 1.0);
    vec2 toLight = (uLightPos - uv) * aspectV;
    vec2 lightDir = toLight / max(length(toLight), 1e-4);
    vec2 lightDirPaint = vec2(lightDir.x, -lightDir.y * uPaintAspect);
    float scatter = exp(-length(toLight) * 3.2);

    // ---- Plate: sky, far mountains and painted fog.
    vec2 pp = paintUv(uv, uRectPlate);
    float above = step(pp.y, 0.0);
    vec2 ppx = extendSky(pp);
    vec4 m = texture(uMask, ppx);
    float skyW = mix(m.b, 1.0, above);
    float farW = m.a * (1.0 - above);
    float fogW = clamp(1.0 - skyW - farW, 0.0, 1.0);
    float depth = clamp((ppx.y - uHorizon) / (1.0 - uHorizon), 0.0, 1.0);

    vec4 fog = texture(uFog, vUv);
    vec4 fogL = texture(uFog, vUv + lightDir / aspectV * 0.02);

    // Flow map with two phase-shifted samples cross-faded, de-synced by low-frequency noise.
    float ph0 = fract(uFlowTime + fog.a);
    float ph1 = fract(ph0 + 0.5);
    float wf = abs(ph0 - 0.5) * 2.0;
    vec2 flow = vec2(uFlowFog * (0.3 + depth) * fogW * fogW + uFlowSky * skyW, 0.0);
    vec3 paint = toLinear(mix(texture(uPlate, ppx - flow * ph0).rgb,
                              texture(uPlate, ppx - flow * ph1).rgb, wf));
    float l = dot(paint, LUMA);

    // Sky: the time-of-day gradient carries the painted clouds as a luminance ratio.
    float h = clamp((uv.y - uScreenHorizon) / (1.0 - uScreenHorizon), 0.0, 1.0);
    vec3 grad = mix(uSkyHorizon, uSkyTop, pow(h, 0.6));
    // The mirrored extension above the painting carries fainter cloud detail, hiding the mirror seam.
    float aboveFade = smoothstep(-0.06, 0.2, -pp.y);
    float ratio = 1.0 + (l / uSkyRef - 1.0) * mix(1.35, 0.45, aboveFade);
    // Thicker, brighter cloud parts catch the key light (sun by day, moon by night), so the clouds
    // stay readable at night instead of vanishing into the dark gradient.
    float cloudLit = max(ratio - 0.95, 0.0);
    vec3 skyCol = grad * ratio + uSun * uGlow * 0.18 * max(ratio - 1.0, 0.0) + uLightColor * cloudLit * 0.35;

    // Far mountains keep the painted colour under the ambient light, fading into the horizon.
    vec3 farCol = mix(paint * uAmbient, uSkyHorizon, uHaze * 0.55);

    // Painted fog keeps its light and shade, recoloured to the hour.
    float fl = smoothstep(0.06, 0.85, l);
    vec3 tint = paint / max(l, 1e-3);
    vec3 fogCol = mix(uFogShadow, uFogLit, fl) * mix(vec3(1.0), tint, 0.2);
    fogCol = mix(fogCol, uSkyHorizon, (1.0 - depth) * 0.3) + uLightColor * scatter * fl * 0.45;

    vec3 col = skyCol * skyW + farCol * farW + fogCol * fogW;

    // Sun halo lights the sky and, more softly, the fog; a warm band hugs the horizon under the sun.
    vec2 ds = (uv - uSunPos) * aspectV;
    float dsun = length(ds);
    float halo = uGlow * uSunVis * (0.45 * exp(-dsun * 5.0) + 0.4 * exp(-dsun * 18.0));
    float band = uGlow * uSunVis * 0.35 * exp(-abs(uv.y - uScreenHorizon) * 9.0) * exp(-abs(ds.x) * 2.2);
    col += uSun * (halo * (skyW + 0.5 * fogW + 0.25 * farW) + band * (skyW + fogW));
    float veil = 0.55 + 0.45 * smoothstep(1.12, 0.92, ratio);
    col = mix(col, uSun * 3.0, smoothstep(0.019, 0.012, dsun) * uSunVis * veil * skyW * skyW);

    if (uStars > 0.001) {
        // Pale summits read as sky in the mask, so stars stop above the highest peak.
        float gaps = smoothstep(1.2, 0.9, ratio);
        col += vec3(0.85, 0.9, 1.0) * stars(uv) * uStars * skyW * skyW * gaps * smoothstep(0.27, 0.21, ppx.y);  // none below the summits (painting v)
    }
    if (uMoonVis > 0.001) {
        float disk;
        vec3 mc = moon(uv, disk);
        float illum = 0.5 * (1.0 - cos(uMoonPhase * 6.2831853));
        float dm = length((uv - uMoonPos) * aspectV);
        col += vec3(0.55, 0.65, 0.85) * 0.12 * illum * exp(-dm * 9.0) * uMoonVis * (skyW + 0.5 * fogW);
        col = mix(col, mc, disk * uMoonVis * skyW * veil);
        // Silver lining: clouds near the moon glow instead of darkening its disc.
        col += vec3(0.75, 0.8, 0.95) * cloudLit * 1.6 * illum * exp(-dm * 7.0) * uMoonVis * skyW;
    }

    // ---- Procedural haze over the far mountains.
    vec3 hazeCol = mix(mix(uFogShadow, uFogLit, 0.75), uSkyHorizon, 0.4) + uLightColor * scatter * 0.3;
    col = mix(col, hazeCol, fog.r * 0.45 * (1.0 - above));

    // Rim light is subtle under a high white sun and strong at golden hour / sunset.
    float rimK = mix(0.2, 1.0, smoothstep(0.4, 1.1, uGlow)) + 0.6 * (1.0 - uSunVis);

    // ---- Mid rocks.
    vec2 pm = paintUv(uv, uRectMid);
    float ma = texture(uMask, pm).r * step(0.0, pm.y);
    if (ma > 0.004) {
        vec3 rc = toLinear(texture(uBase, pm).rgb);
        ma = solidAlpha(ma, rc, 0.3, 0.6);
        rc *= uAmbient;
        float rim = clamp(ma - texture(uMask, pm + lightDirPaint * 0.005).r, 0.0, 1.0);
        rc += uLightColor * rim * 0.5 * rimK;
        rc = mix(rc, mix(uFogShadow, uFogLit, 0.5), uHaze * 0.3);
        col = mix(col, rc, ma);
    }

    // ---- Fog sea swallowing the rock bases.
    col = mix(col, shadeFog(fog.g, fogL.g, depth, scatter), fog.g * 0.35);

    // ---- The wanderer and his crag.
    vec2 pf = paintUv(uv, uRectFg);
    // Tighten the silhouette so the coat has no see-through fringe.
    float fa = smoothstep(0.4, 0.75, texture(uMask, pf).g) * step(0.0, pf.y);
    if (fa > 0.004) {
        vec3 fc = toLinear(texture(uBase, pf).rgb);
        fa = solidAlpha(fa, fc, 0.25, 0.5);
        fc *= uAmbient;
        float rim = clamp(fa - texture(uMask, pf + lightDirPaint * 0.0025).g, 0.0, 1.0);
        // Rim light only on dark rock and cloth: pale bits (grass, wisps) would get a bright cut-out edge.
        fc += uLightColor * rim * 0.6 * rimK * smoothstep(0.35, 0.1, dot(fc, LUMA));
        col = mix(col, fc, fa);
    }

    // ---- Wisps in front.
    col = mix(col, shadeFog(fog.b, fogL.b, 1.0, scatter), fog.b * 0.22 * (1.0 - fa));

    // Vignette, soft shoulder for the bright sun, gamma and dithering against banding.
    vec2 v = vUv - 0.5;
    col *= 1.0 - 0.22 * dot(v, v) * 2.0;
    col = mix(col, 1.0 - 0.2 * exp(-(col - 0.8) / 0.2), step(0.8, col));
    col = pow(max(col, 0.0), vec3(1.0 / 2.2));
    col += (hash(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
