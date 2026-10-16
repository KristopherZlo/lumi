package io.github.lumi.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Opt-in backend diagnostic: retain Fabric's virtual viewport without resizing the OS surface. */
@Mixin(value = Window.class, priority = 900)
public abstract class FixedGameTestWindowMixin {
    @WrapOperation(method = "setWindowSizeAndPosition", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/sdl/SDLVideo;SDL_SetWindowSize(JII)Z"))
    private boolean lumi$keepPhysicalSize(long window, int width, int height, Operation<Boolean> original) {
        return Boolean.getBoolean("lumi.gametest.fixedNativeWindow") || original.call(window, width, height);
    }
}
