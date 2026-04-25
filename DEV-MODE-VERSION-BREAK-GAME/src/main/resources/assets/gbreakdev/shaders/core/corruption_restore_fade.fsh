#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (vertexColor.a <= 0.0) {
        discard;
    }

    fragColor = vec4(1.0, 1.0, 1.0, vertexColor.a) * ColorModulator;
}
