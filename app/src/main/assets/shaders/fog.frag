#version 300 es
// Low-resolution pass: procedural fog densities, later upscaled bilinearly by scene.frag.
// R = haze band over the far mountains, G = fog sea in front of the mid rocks,
// B = wisps in front of the wanderer, A = low-frequency noise used to de-sync the painted-fog flow.
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform vec4 uRect;        // screen -> painting uv for the fog plane (xy scale, zw offset)
uniform float uAspect;     // painting width / height
uniform float uHorizon;    // painting v of the horizon (0 = top)
uniform int uOctaves;
uniform vec2 uOffset0;     // per-layer drift, wrapped to the noise period on the CPU
uniform vec2 uOffset1;
uniform vec2 uOffset2;
uniform vec2 uWarp;
uniform float uParallax;   // home-screen offset - 0.5; the painting pans as one piece, nearer fog pans further
uniform vec2 uFogDepth;    // extra pan of the fog sea (x) and the wisps (y)

const float PERIOD = 32.0;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Value noise that tiles every `period` cells, so drifting offsets can wrap without a seam.
float vnoise(vec2 p, float period) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(mod(i, period));
    float b = hash(mod(i + vec2(1.0, 0.0), period));
    float c = hash(mod(i + vec2(0.0, 1.0), period));
    float d = hash(mod(i + vec2(1.0, 1.0), period));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p, int octaves) {
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    float period = PERIOD;
    for (int i = 0; i < 6; i++) {
        if (i >= octaves) break;
        sum += amp * vnoise(p, period);
        norm += amp;
        p = p * 2.0 + vec2(17.3, 9.1);
        period *= 2.0;
        amp *= 0.5;
    }
    return sum / norm;
}

// A horizontal fog plane seen in perspective: rows near the horizon are far away and compressed.
float fogPlane(vec2 pv, vec2 offset, float scale, float cover, float soft) {
    float dy = max(pv.y - uHorizon, 0.0);
    float z = 0.12 / (dy + 0.012);
    vec2 p = vec2((pv.x - 0.5) * uAspect * z * 1.6, z) * scale;
    float w = fbm(p * 0.5 + uWarp, 2);
    float n = fbm(p + offset + vec2(w * 1.3, w * 0.4), uOctaves);
    return smoothstep(cover, cover + soft, n);
}

void main() {
    vec2 q = vec2(vUv.x, 1.0 - vUv.y);
    vec2 pv = q * uRect.xy + uRect.zw;

    float haze = 0.0;
    float sea = 0.0;
    float wisps = 0.0;
    float h = uHorizon;

    // Haze band hugging the far mountains just above the horizon.
    if (pv.y > h - 0.09 && pv.y < h + 0.06) {
        vec2 p = vec2(pv.x * uAspect * 3.0, pv.y * 22.0);
        float n = fbm(p + uOffset0, uOctaves);
        float band = smoothstep(h - 0.09, h - 0.02, pv.y) * smoothstep(h + 0.06, h + 0.01, pv.y);
        haze = smoothstep(0.35, 0.75, n) * band;
    }
    // Fog sea that swallows the bases of the mid rocks.
    if (pv.y > h + 0.03 && pv.y < 0.86) {
        float d = fogPlane(pv + vec2(uParallax * uFogDepth.x, 0.0), uOffset1, 1.0, 0.5, 0.28);
        // Start low: higher up the plane compresses into flat horizontal streaks across the rocks.
        float band = smoothstep(h + 0.16, h + 0.26, pv.y) * smoothstep(0.86, 0.7, pv.y);
        sea = d * band;
    }
    // Thin wisps drifting past the wanderer's crag.
    if (pv.y > 0.5 && pv.y < 0.85) {
        vec2 p = vec2((pv.x + uParallax * uFogDepth.y) * uAspect * 2.2, pv.y * 4.0);
        float n = fbm(p + uOffset2, uOctaves);
        wisps = smoothstep(0.6, 0.88, n) * smoothstep(0.5, 0.6, pv.y) * smoothstep(0.85, 0.72, pv.y);
    }
    float phase = vnoise(q * vec2(3.0, 5.0), PERIOD);
    fragColor = vec4(haze, sea, wisps, phase);
}
