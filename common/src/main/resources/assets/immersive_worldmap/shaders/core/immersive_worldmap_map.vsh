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

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 mapPosition;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    mapPosition = Position.xz * Map.z + Map.xy;
}
