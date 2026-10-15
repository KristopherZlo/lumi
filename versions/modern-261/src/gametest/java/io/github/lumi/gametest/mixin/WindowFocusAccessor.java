package io.github.lumi.gametest.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives hidden-window interaction tests the same focus control as the legacy client API. */
@Mixin(Window.class)
public interface WindowFocusAccessor {
    @Accessor("focused") void lumi$setFocused(boolean focused);
}
