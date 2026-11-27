#version 330 core

layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec3 aNormal;
layout (location = 2) in vec2 aUv;
layout (location = 3) in uint aMaterial;
layout (location = 4) in float aAlpha;
layout (location = 5) in vec2 aOverlayUv;
layout (location = 6) in uint aOverlayMaterial;

out vec3 vNormal;
out vec2 vUv;
out vec2 vOverlayUv;
flat out vec4 vColor;
flat out vec3 vOverlayColor;
flat out float vTextured;
flat out float vShaded;
flat out float vHasOverlay;

uniform mat4 uMvp;
uniform vec3 uOffset;
uniform bool uVertexMaterial;
uniform vec4 uColor;
uniform bool uTextured;
uniform bool uShaded;

void main() {
    gl_Position = uMvp * vec4(aPosition + uOffset, 1.0);
    vNormal = aNormal;
    vUv = aUv;
    vOverlayUv = aOverlayUv;
    if (uVertexMaterial) {
        uvec3 rgb = uvec3(aMaterial >> 16u, aMaterial >> 8u, aMaterial) & 255u;
        vColor = vec4(vec3(rgb) / 255.0, aAlpha);
        vTextured = float((aMaterial >> 24u) & 1u);
        vShaded = float((aMaterial >> 25u) & 1u);
        vHasOverlay = float((aMaterial >> 26u) & 1u);
        uvec3 overlayRgb = uvec3(aOverlayMaterial >> 16u, aOverlayMaterial >> 8u, aOverlayMaterial) & 255u;
        vOverlayColor = vec3(overlayRgb) / 255.0;
    } else {
        vColor = uColor;
        vTextured = float(uTextured);
        vShaded = float(uShaded);
        vHasOverlay = 0.0;
        vOverlayColor = vec3(1.0);
    }
}
