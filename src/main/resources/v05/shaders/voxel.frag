#version 330 core

in vec3 vNormal;

in vec2 vUv;
uniform sampler2D uAtlas;
uniform bool uTextured;
uniform bool uShaded;
uniform vec4 uColor;

out vec4 fragColor;

void main() {
    vec3 normal = normalize(vNormal);
    vec3 lightDirection = normalize(vec3(-1.0, 1.0, -0.75));
    float diffuse = max(dot(normal, lightDirection), 0.0);
    float brightness = uShaded ? 0.55 + diffuse * 0.45 : 1.0;
    vec4 color = uTextured ? texture(uAtlas, vUv) * uColor : uColor;
    if (color.a < 0.1) discard;
    fragColor = vec4(color.rgb * brightness, color.a);
}
