package io.github.luma.minecraft.testing;

enum SingleplayerTestMode {
    FULL("singleplayer testing"),
    SMOKE("singleplayer smoke testing"),
    STRUCTURE_FIXTURES("singleplayer structure fixture testing");

    private final String label;

    SingleplayerTestMode(String label) {
        this.label = label;
    }

    String label() {
        return this.label;
    }
}
