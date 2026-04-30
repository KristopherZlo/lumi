package io.github.luma.ui.onboarding;

import io.github.luma.LumaMod;
import net.minecraft.resources.Identifier;

public record KeyGlyph(String spriteName, int frameWidth, int height) {

    public Identifier textureId() {
        return Identifier.fromNamespaceAndPath(
                LumaMod.MOD_ID,
                "textures/gui/dark_buttons/" + this.spriteName + ".png"
        );
    }

    public int textureWidth() {
        return this.frameWidth * 3;
    }
}
