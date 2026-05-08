package io.github.luma.ui.preview;

import io.github.luma.LumaMod;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class LoadingAnimationComponent extends BaseUIComponent {

    private static final int FRAME_COUNT = 8;
    private static final int TEXTURE_SIZE = 16;
    private static final long FRAME_MILLIS = 90L;
    private static final Identifier[] FRAMES = frames();

    private final int size;

    public LoadingAnimationComponent(int size) {
        this.size = Math.max(TEXTURE_SIZE, size);
        this.sizing(Sizing.fixed(this.size), Sizing.fixed(this.size));
    }

    @Override
    protected int determineHorizontalContentSize(Sizing sizing) {
        return this.size;
    }

    @Override
    protected int determineVerticalContentSize(Sizing sizing) {
        return this.size;
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        int frame = (int) ((System.currentTimeMillis() / FRAME_MILLIS) % FRAME_COUNT);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                FRAMES[frame],
                this.x,
                this.y,
                0,
                0,
                this.size,
                this.size,
                TEXTURE_SIZE,
                TEXTURE_SIZE,
                TEXTURE_SIZE,
                TEXTURE_SIZE
        );
    }

    private static Identifier[] frames() {
        Identifier[] frames = new Identifier[FRAME_COUNT];
        for (int index = 0; index < FRAME_COUNT; index++) {
            frames[index] = Identifier.fromNamespaceAndPath(
                    LumaMod.MOD_ID,
                    "textures/gui/loading/loading_" + index + ".png"
            );
        }
        return frames;
    }
}
