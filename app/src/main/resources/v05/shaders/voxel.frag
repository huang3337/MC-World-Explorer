#version 330 core

in vec3 vNormal;
in vec2 vUv;
in vec2 vOverlayUv;
uniform sampler2D uAtlas;
flat in vec4 vColor;
flat in vec3 vOverlayColor;
flat in float vTextured;
flat in float vShaded;
flat in float vHasOverlay;

out vec4 fragColor;

void main() {
    vec3 normal = normalize(vNormal);
    vec3 lightDirection = normalize(vec3(-1.0, 1.0, -0.75));
    float diffuse = max(dot(normal, lightDirection), 0.0);
    float brightness = vShaded > 0.5 ? 0.55 + diffuse * 0.45 : 1.0;
    vec4 base = vTextured > 0.5 ? texture(uAtlas, vUv) * vColor : vColor;
    vec4 color = base;
    if (vHasOverlay > 0.5) {
        vec4 top = texture(uAtlas, vOverlayUv) * vec4(vOverlayColor, 1.0);
        float outA = top.a + base.a * (1.0 - top.a);
        vec3 outRgb = outA > 0.0 ? (top.rgb * top.a + base.rgb * base.a * (1.0 - top.a)) / outA : vec3(0.0);
        color = vec4(outRgb, outA);
    }
    if (color.a < 0.1) discard;
    fragColor = vec4(color.rgb * brightness, color.a);
}
