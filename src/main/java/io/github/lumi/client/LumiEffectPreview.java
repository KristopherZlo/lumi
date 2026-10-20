package io.github.lumi.client;

import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;

/** Development-only visual samples; commands never invoke history or world edits. */
final class LumiEffectPreview {
    enum Effect { SAVE, BRANCH }
    private static final long DURATION = 1_100_000_000L;
    private Sample active;

    static void register() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        var preview = new LumiEffectPreview();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var command = ClientCommandManager.literal("lumi-effect")
                    .executes(context -> preview.play(Effect.SAVE));
            for (var effect : Effect.values()) command.then(ClientCommandManager
                    .literal(effect.name().toLowerCase(Locale.ROOT)).executes(context -> preview.play(effect)));
            dispatcher.register(command);
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath("lumi", "effect_preview"), (graphics, delta) -> preview.renderHud(graphics));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> preview.active = null);
    }

    private int play(Effect effect) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return 0;
        active = new Sample(client.level, effect, System.nanoTime());
        return 1;
    }

    private Sample current() {
        if (active != null && (active.world != Minecraft.getInstance().level
                || System.nanoTime() - active.started >= DURATION)) active = null;
        return active;
    }

    private double progress(Sample sample) {
        return Math.clamp((double) (System.nanoTime() - sample.started) / DURATION, 0, 1);
    }

    private void renderHud(GuiGraphics graphics) {
        var sample = current();
        var client = Minecraft.getInstance();
        if (sample == null || client.options.hideGui
                || sample.effect != Effect.SAVE && sample.effect != Effect.BRANCH) return;
        double t = progress(sample);
        int alpha = (int) (255 * Math.min(1, t*8) * Math.min(1, (1-t)*5));
        if (alpha < 8) return;
        int width = Math.min(220, graphics.guiWidth()-16), x = (graphics.guiWidth()-width)/2, y = 28;
        graphics.fill(x, y, x+width, y+32, (alpha*4/5) << 24 | 0x141517);
        var font = client.font;
        if (sample.effect == Effect.SAVE) {
            int gap = (int) (10 * (1-Math.min(1,t*4)));
            for (int side : new int[] {-1, 1}) {
                int edge = side < 0 ? x-gap : x+width+gap-1;
                graphics.fill(edge, y-gap, edge+1, y+32+gap, alpha << 24 | 0xd9b86c);
                int left = side < 0 ? edge : edge-8;
                graphics.fill(left, y-gap, left+9, y-gap+1, alpha << 24 | 0xd9b86c);
                graphics.fill(left, y+31+gap, left+9, y+32+gap, alpha << 24 | 0xd9b86c);
            }
            graphics.drawCenteredString(font, "[Preview] Saved: Courtyard", graphics.guiWidth()/2, y+12, alpha << 24 | 0xf4f1ea);
        } else {
            boolean incoming = t >= .4;
            double part = incoming ? Math.min(1,(t-.4)/.35) : 1-t/.4;
            int textAlpha = (int) (alpha*part);
            int shift = (int) ((incoming ? 1 : -1) * (1-part) * 14);
            if (textAlpha >= 8) graphics.drawCenteredString(font, incoming ? "[Preview] roof-study" : "[Preview] main",
                    graphics.guiWidth()/2+shift, y+12, textAlpha << 24 | 0xd9b86c);
        }
    }

    private record Sample(ClientLevel world, Effect effect, long started) { }
}
