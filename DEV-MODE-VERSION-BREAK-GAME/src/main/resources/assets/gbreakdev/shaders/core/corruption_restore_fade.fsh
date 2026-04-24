#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (vertexColor.a <= 0.0) {
        discard;
    }

    float shimmer = 0.84 + 0.16 * sin((gl_FragCoord.x + gl_FragCoord.y) * 0.18);
    fragColor = vec4(1.0, 1.0, 1.0, vertexColor.a * shimmer) * ColorModulator;
}
