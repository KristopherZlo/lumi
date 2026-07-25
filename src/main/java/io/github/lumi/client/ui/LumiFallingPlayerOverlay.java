package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One client-only falling-player animation triggered by a self-named Save. */
public final class LumiFallingPlayerOverlay {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "falling_player_easter_egg");
    private static final long DURATION_MILLIS = 2_200L;
    private static final int MODEL_WIDTH = 54;
    private static final int MODEL_HEIGHT = 74;
    private static final float PLAYER_HEIGHT = 2.125F;
    private long startedAt = Long.MIN_VALUE;
    private Supplier<PlayerSkin> skin = DefaultPlayerSkin::getDefaultSkin;

    public void register() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT, ID, (graphics, ignored) -> {
                    if (!(Minecraft.getInstance().screen instanceof LumiScreen)) {
                        render(graphics);
                    }
                });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof LumiScreen) {
                ScreenEvents.afterRender(screen).register(
                        (current, graphics, mouseX, mouseY, tickDelta) -> {
                            if (client.screen == current) render(graphics);
                        });
            }
        });
    }

    public void triggerIfPlayerName(String saveName) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null
                || !matchesPlayerName(
                        saveName, client.player.getName().getString())) {
            return;
        }
        var lookup = client.playerSkinRenderCache().createLookup(
                ResolvableProfile.createUnresolved(
                        client.player.getName().getString()));
        skin = () -> lookup.get().playerSkin();
        startedAt = System.currentTimeMillis();
    }

    private void render(GuiGraphics graphics) {
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed < 0 || elapsed >= DURATION_MILLIS) return;
        float progress = elapsed / (float) DURATION_MILLIS;
        int x = (graphics.guiWidth() - MODEL_WIDTH) / 2;
        int y = fallY(progress, graphics.guiHeight(), MODEL_HEIGHT);
        AvatarRenderState state = renderState(skin.get(), elapsed);
        Quaternionf camera = new Quaternionf();
        Quaternionf model = new Quaternionf()
                .rotateZ((float) Math.PI
                        + (float) Math.sin(progress * Math.PI * 6.0F) * 0.18F);
        graphics.submitEntityRenderState(
                state, MODEL_HEIGHT / PLAYER_HEIGHT,
                new Vector3f(0.0F, PLAYER_HEIGHT / 2.0F, 0.0F),
                model, camera, x, y, x + MODEL_WIDTH, y + MODEL_HEIGHT);
    }

    private static AvatarRenderState renderState(PlayerSkin skin, long elapsed) {
        AvatarRenderState state = new AvatarRenderState();
        state.skin = skin;
        state.showCape = skin.cape() != null;
        state.lightCoords = LightTexture.FULL_BRIGHT;
        state.bodyRot = 180.0F;
        state.walkAnimationPos = elapsed / 45.0F;
        state.walkAnimationSpeed = 1.0F;
        state.capeFlap = 24.0F;
        return state;
    }

    static boolean matchesPlayerName(String saveName, String playerName) {
        return saveName != null && playerName != null
                && saveName.trim().equalsIgnoreCase(playerName);
    }

    static int fallY(float progress, int screenHeight, int modelHeight) {
        float eased = progress * progress;
        return Math.round(-modelHeight + eased * (screenHeight + modelHeight * 2));
    }
}
