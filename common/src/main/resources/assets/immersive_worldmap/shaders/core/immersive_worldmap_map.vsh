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

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
out vec2 mapPosition;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    mapPosition = Position.xz * Map.z + Map.xy;
}
