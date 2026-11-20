#version 330 core

in vec3 vNormal;

in vec2 vUv;
uniform sampler2D uAtlas;
flat in vec4 vColor;
flat in float vTextured;
flat in float vShaded;

out vec4 fragColor;

void main() {
    vec3 normal = normalize(vNormal);
    vec3 lightDirection = normalize(vec3(-1.0, 1.0, -0.75));
    float diffuse = max(dot(normal, lightDirection), 0.0);
    float brightness = vShaded > 0.5 ? 0.55 + diffuse * 0.45 : 1.0;
    vec4 color = vTextured > 0.5 ? texture(uAtlas, vUv) * vColor : vColor;
    if (color.a < 0.1) discard;
    fragColor = vec4(color.rgb * brightness, color.a);
}
