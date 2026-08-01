#version 330 core

in vec3 vNormal;

uniform vec3 uColor;

out vec4 fragColor;

void main() {
    vec3 normal = normalize(vNormal);
    vec3 lightDirection = normalize(vec3(-1.0, 1.0, -0.75));
    float diffuse = max(dot(normal, lightDirection), 0.0);
    float brightness = 0.55 + diffuse * 0.45;
    fragColor = vec4(uColor * brightness, 1.0);
}
