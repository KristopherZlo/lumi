package io.github.luma.client.specialthanks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

public final class SpecialThanksCapeRenderRegistry {

    private static final int FULL_BRIGHT = 15728880;
    private static final SpecialThanksCapeRenderRegistry INSTANCE = new SpecialThanksCapeRenderRegistry();

    private final Map<PlayerModel, AttachedCape> capes = new WeakHashMap<>();

    private SpecialThanksCapeRenderRegistry() {
    }

    public static SpecialThanksCapeRenderRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized void attach(PlayerModel playerModel, PlayerCapeModel capeModel, Identifier texture) {
        if (playerModel == null || capeModel == null || texture == null) {
            this.clear(playerModel);
            return;
        }
        this.capes.put(playerModel, new AttachedCape(capeModel, texture));
    }

    public synchronized void clear(PlayerModel playerModel) {
        if (playerModel != null) {
            this.capes.remove(playerModel);
        }
    }

    public synchronized void renderAttachedCape(
            PlayerModel playerModel,
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource
    ) {
        AttachedCape cape = this.capes.get(playerModel);
        if (cape == null || poseStack == null || bufferSource == null) {
            return;
        }
        cape.model().renderToBuffer(
                poseStack,
                bufferSource.getBuffer(cape.model().renderType(cape.texture())),
                FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY
        );
    }

    private record AttachedCape(PlayerCapeModel model, Identifier texture) {
    }
}
