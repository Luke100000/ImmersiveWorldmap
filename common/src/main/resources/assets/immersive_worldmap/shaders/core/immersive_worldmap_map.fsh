#version 330
#extension GL_ARB_separate_shader_objects : require

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

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 mapPosition;

layout(location = 0) out vec4 fragColor;

void main() {
    float fade = smoothstep(Fade.x, Fade.y, length(mapPosition));
    vec2 screenUv = gl_FragCoord.xy / Fade.zw;
    screenUv.y = 1.0 - screenUv.y;
    vec3 background = texture(BackgroundTexture, screenUv * BackgroundUv.xy + BackgroundUv.zw).rgb;
    // Keep terrain opaque so overlapping surfaces retain their normal depth ordering.
    fragColor = vec4(mix(vertexColor.rgb, background, fade), 1.0);
}
