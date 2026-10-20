package io.github.lumi.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Development-only visual samples; commands never invoke history or world edits. */
final class LumiEffectPreview {
    enum Effect { UNDO, REDO, RESTORE, SAVE, BRANCH }
    private static final long DURATION = 1_100_000_000L;
    private final LumiOverlaySubmission geometry = new LumiOverlaySubmission();
    private Sample active;

    static void register() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        var preview = new LumiEffectPreview();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var command = ClientCommandManager.literal("lumi-effect")
                    .executes(context -> preview.play(Effect.UNDO));
            for (var effect : Effect.values()) command.then(ClientCommandManager
                    .literal(effect.name().toLowerCase(Locale.ROOT)).executes(context -> preview.play(effect)));
            dispatcher.register(command);
        });
        //? if >=26.2 {
        /*WorldRenderEvents.COLLECT_SUBMITS.register(preview::renderWorld);
        *///?} else {
        WorldRenderEvents.END_MAIN.register(preview::renderWorld);
        //?}
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath("lumi", "effect_preview"), (graphics, delta) -> preview.renderHud(graphics));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> preview.active = null);
    }

    private int play(Effect effect) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return 0;
        var hit = client.player.pick(12, 1, false);
        var normal = client.player.getViewVector(1).scale(-1);
        var center = hit.getType() == HitResult.Type.MISS
                ? client.player.getEyePosition().add(normal.scale(-5)) : hit.getLocation();
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            var face = block.getDirection();
            normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        }
        var up = Math.abs(normal.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        var right = up.cross(normal).normalize();
        up = normal.cross(right).normalize();
        active = new Sample(client.level, effect, center.add(normal.scale(.04)), normal, right, up, System.nanoTime());
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

    private void renderWorld(WorldRenderContext context) {
        var sample = current();
        if (sample == null || sample.effect == Effect.SAVE || sample.effect == Effect.BRANCH) return;
        double t = progress(sample);
        var center = sample.center.subtract(context.worldState().cameraRenderState.pos)
                .add(sample.normal.scale(Math.sin(t * Math.PI) * .15));
        geometry.submit(context, LumiCompareRenderTypes.fill(true), (pose, vertices) ->
                renderShape(pose, vertices, sample.effect, center, sample.right, sample.up, t));
    }

    static void renderShape(PoseStack.Pose pose, VertexConsumer vertices, Effect effect,
            Vec3 center, Vec3 right, Vec3 up, double t) {
        int color = effect == Effect.UNDO ? 0xff8585 : effect == Effect.REDO ? 0x79d49a : 0xd9b86c;
        int alpha = (int) (240 * Math.min(1, t * 10) * Math.min(1, (1-t) * 4));
        int argb = alpha << 24 | color;
        double eased = 1 - Math.pow(1-t, 3);
        if (effect == Effect.RESTORE) {
            double x = -1.5 + 3*eased;
            vertex(pose, vertices, center, right, up, x-.045, -1.5, argb);
            vertex(pose, vertices, center, right, up, x+.045, -1.5, argb);
            vertex(pose, vertices, center, right, up, x+.045, 1.5, argb);
            vertex(pose, vertices, center, right, up, x-.045, 1.5, argb);
            return;
        }
        double radius = .1 + 1.4 * (effect == Effect.UNDO ? 1-eased : eased);
        for (int i = 0; i < 64; i++) {
            double a = i*Math.PI/32, b = (i+1)*Math.PI/32;
            double ax = Math.cos(a), ay = Math.sin(a), bx = Math.cos(b), by = Math.sin(b);
            vertex(pose, vertices, center, right, up, ax*(radius-.035), ay*(radius-.035), argb);
            vertex(pose, vertices, center, right, up, bx*(radius-.035), by*(radius-.035), argb);
            vertex(pose, vertices, center, right, up, bx*(radius+.035), by*(radius+.035), argb);
            vertex(pose, vertices, center, right, up, ax*(radius+.035), ay*(radius+.035), argb);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices, Vec3 center,
            Vec3 right, Vec3 up, double x, double y, int color) {
        vertices.addVertex(pose, (float) (center.x + right.x*x + up.x*y),
                (float) (center.y + right.y*x + up.y*y), (float) (center.z + right.z*x + up.z*y)).setColor(color);
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

    private record Sample(ClientLevel world, Effect effect, Vec3 center, Vec3 normal,
            Vec3 right, Vec3 up, long started) { }
}
