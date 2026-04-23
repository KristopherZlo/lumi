package io.github.luma.gbreak.mixin.client;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Invoker("setPostProcessor")
    void gbreak$setPostProcessor(Identifier id);

    @Accessor("BLUR_ID")
    static Identifier gbreak$getBlurId() {
        throw new AssertionError();
    }
}
