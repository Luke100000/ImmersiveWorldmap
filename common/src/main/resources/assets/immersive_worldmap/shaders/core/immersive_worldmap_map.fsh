#version 330

layout(std140) uniform MapInfo {
    mat4 ModelViewMat;
    mat4 ProjMat;
    vec4 BackgroundUv;
    // x, y: map offset, z: map scale
    vec4 Map;
    // x: fade start, y: fade end, zw: screen size
    vec4 Fade;
};

uniform sampler2D BackgroundTexture;

in vec4 vertexColor;
in vec2 mapPosition;

out vec4 fragColor;

void main() {
    float fade = smoothstep(Fade.x, Fade.y, length(mapPosition));
    vec2 screenUv = gl_FragCoord.xy / Fade.zw;
    screenUv.y = 1.0 - screenUv.y;
    vec3 background = texture(BackgroundTexture, screenUv * BackgroundUv.xy + BackgroundUv.zw).rgb;
    // Keep terrain opaque so overlapping surfaces retain their normal depth ordering.
    fragColor = vec4(mix(vertexColor.rgb, background, fade), 1.0);
}
