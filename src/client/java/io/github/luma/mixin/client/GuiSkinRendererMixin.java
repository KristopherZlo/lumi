package io.github.luma.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.luma.client.specialthanks.SpecialThanksCapeRenderRegistry;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.gui.render.state.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiSkinRenderer.class)
abstract class GuiSkinRendererMixin {

    @Shadow
    @Final
    protected MultiBufferSource.BufferSource bufferSource;

    @Inject(
            method = "renderToTexture(Lnet/minecraft/client/gui/render/state/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V"
            )
    )
    private void luma$renderAttachedCape(GuiSkinRenderState state, PoseStack poseStack, CallbackInfo ci) {
        SpecialThanksCapeRenderRegistry.getInstance().renderAttachedCape(
                state.playerModel(),
                poseStack,
                this.bufferSource
        );
    }
}
