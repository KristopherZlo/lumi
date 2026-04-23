package io.github.luma.gbreak.client;

import io.github.luma.gbreak.bug.GameBreakingBug;
import io.github.luma.gbreak.state.BugStateController;
import java.util.Random;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

final class GlobalCorruptionHudRenderer {

    private final BugStateController bugState = BugStateController.getInstance();

    void render(DrawContext drawContext, MinecraftClient client) {
        if (client == null
                || client.world == null
                || client.textRenderer == null
                || !this.bugState.isActive(GameBreakingBug.GLOBAL_CORRUPTION)) {
            return;
        }

        long seed = (client.world.getTime() * 31L) + (System.nanoTime() / 60_000_000L);
        Random random = new Random(seed);
        int width = drawContext.getScaledWindowWidth();
        int height = drawContext.getScaledWindowHeight();
        int pulse = 45 + (int) Math.round((Math.sin(client.world.getTime() * 0.18D) + 1.0D) * 32.0D);
        int fullscreenTint = (pulse << 24) | 0x280900;
        drawContext.fill(0, 0, width, height, fullscreenTint);

        for (int index = 0; index < 22; index++) {
            int x = random.nextInt(Math.max(1, width));
            int y = random.nextInt(Math.max(1, height));
            int glitchWidth = 40 + random.nextInt(Math.max(40, width / 3));
            int glitchHeight = 2 + random.nextInt(26);
            int alpha = 70 + random.nextInt(130);
            int color = (alpha << 24)
                    | (random.nextInt(256) << 16)
                    | (random.nextInt(256) << 8)
                    | random.nextInt(256);
            drawContext.fill(x, y, Math.min(width, x + glitchWidth), Math.min(height, y + glitchHeight), color);
        }

        for (int index = 0; index < 8; index++) {
            int y = MathHelper.clamp((index * height) / 8 + random.nextInt(18) - 9, 0, height);
            int alpha = 32 + random.nextInt(80);
            drawContext.fill(0, y, width, Math.min(height, y + 1 + random.nextInt(3)), (alpha << 24) | 0xFFFFFF);
        }

        for (int index = 0; index < 4; index++) {
            String message = CorruptedTextGenerator.corrupt(switch (index % 4) {
                case 0 -> "ui.translation_table_missing :: renderer fault";
                case 1 -> "shader pipeline mismatch :: frame skipped";
                case 2 -> "glyph atlas corrupted :: fallback aborted";
                default -> "screen buffer desync :: stale widgets detected";
            });
            int textX = 8 + random.nextInt(Math.max(8, width / 3));
            int textY = MathHelper.clamp(10 + random.nextInt(Math.max(12, height - 20)), 4, Math.max(4, height - 12));
            int color = 0xFF000000 | (random.nextInt(256) << 16) | (40 + random.nextInt(216));
            drawContext.drawText(client.textRenderer, message, textX, textY, color, true);
        }
    }
}
