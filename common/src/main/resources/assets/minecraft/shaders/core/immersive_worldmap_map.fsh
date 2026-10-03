#version 150

uniform sampler2D BackgroundTexture;
uniform vec4 ColorModulator;
uniform vec2 ScreenSize;
uniform vec4 BackgroundUv;
uniform float FadeStart;
uniform float FadeEnd;

in vec4 vertexColor;
in vec2 mapPosition;

out vec4 fragColor;

void main() {
    float fade = smoothstep(FadeStart, FadeEnd, length(mapPosition));
    vec2 screenUv = gl_FragCoord.xy / ScreenSize;
    screenUv.y = 1.0 - screenUv.y;
    vec3 background = texture(BackgroundTexture, screenUv * BackgroundUv.xy + BackgroundUv.zw).rgb;
    // Keep terrain opaque so overlapping surfaces retain their normal depth ordering.
    fragColor = vec4(mix((vertexColor * ColorModulator).rgb, background, fade), 1.0);
}
