package io.github.luma.ui.onboarding;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.function.BooleanSupplier;
import net.minecraft.client.renderer.RenderPipelines;

public final class KeyGlyphComponent extends BaseUIComponent {

    private final KeyGlyph glyph;
    private final BooleanSupplier pressed;

    public KeyGlyphComponent(KeyGlyph glyph, BooleanSupplier pressed) {
        this.glyph = glyph;
        this.pressed = pressed == null ? () -> false : pressed;
        this.sizing(Sizing.fixed(glyph.frameWidth()), Sizing.fixed(glyph.height()));
    }

    @Override
    protected int determineHorizontalContentSize(Sizing sizing) {
        return this.glyph.frameWidth();
    }

    @Override
    protected int determineVerticalContentSize(Sizing sizing) {
        return this.glyph.height();
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        int frame = this.pressed.getAsBoolean() ? 2 : 0;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                this.glyph.textureId(),
                this.x,
                this.y,
                frame * this.glyph.frameWidth(),
                0,
                this.glyph.frameWidth(),
                this.glyph.height(),
                this.glyph.frameWidth(),
                this.glyph.height(),
                this.glyph.textureWidth(),
                this.glyph.height()
        );
    }
}
