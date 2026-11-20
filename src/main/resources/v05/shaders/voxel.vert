#version 330 core

layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec3 aNormal;

layout (location = 2) in vec2 aUv;
layout (location = 3) in uint aMaterial;
layout (location = 4) in float aAlpha;
out vec2 vUv;
flat out vec4 vColor;
flat out float vTextured;
flat out float vShaded;

uniform mat4 uMvp;
uniform vec3 uOffset;
uniform bool uVertexMaterial;
uniform vec4 uColor;
uniform bool uTextured;
uniform bool uShaded;

out vec3 vNormal;

void main() {
    gl_Position = uMvp * vec4(aPosition + uOffset, 1.0);
    vNormal = aNormal;
    vUv = aUv;
    if (uVertexMaterial) {
        uvec3 rgb = uvec3(aMaterial >> 16u, aMaterial >> 8u, aMaterial) & 255u;
        vColor = vec4(vec3(rgb) / 255.0, aAlpha);
        vTextured = float((aMaterial >> 24u) & 1u);
        vShaded = float((aMaterial >> 25u) & 1u);
    } else {
        vColor = uColor;
        vTextured = float(uTextured);
        vShaded = float(uShaded);
    }
}
