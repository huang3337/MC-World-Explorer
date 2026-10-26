#version 330 core

layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec3 aNormal;

layout (location = 2) in vec2 aUv;
out vec2 vUv;

uniform mat4 uMvp;
uniform vec3 uOffset;

out vec3 vNormal;

void main() {
    gl_Position = uMvp * vec4(aPosition + uOffset, 1.0);
    vNormal = aNormal;
    vUv = aUv;
}
