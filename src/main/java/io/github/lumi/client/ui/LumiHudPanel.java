package io.github.lumi.client.ui;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** One bounded HUD surface; animation never changes operation state or progress. */
final class LumiHudPanel {
    record Content(Object identity, String title, String detail, boolean compact,
            boolean saved, boolean error, boolean busy, OptionalDouble progress,
            List<String> shortcuts) {
        Content {
            shortcuts = List.copyOf(shortcuts.subList(0, Math.min(3, shortcuts.size())));
        }
    }

    private LumiMotion height = new LumiMotion(24), opacity = new LumiMotion();
    private final LumiMotion transition = new LumiMotion();
    private Content current, previous;
    private long entered;

    void clear() {
        current = previous = null;
        height = new LumiMotion(24);
        opacity = new LumiMotion();
    }

    void render(GuiGraphics graphics, Content next, int y) {
        int width = Math.min(224, graphics.guiWidth() - 20);
        if (width < 24) return;
        if (current == null || !Objects.equals(current.identity(), next.identity())) {
            previous = current == null ? next : current;
            transition.start(300);
            entered = System.nanoTime();
        }
        current = next;
        int lines = detailLines(next, width);
        int targetHeight = contentHeight(next.compact(), lines, next.busy(), next.shortcuts().size());
        int panelHeight = Math.round(height.toward(targetHeight));
        float alpha = opacity.toward(1), mix = transition.value();
        if (alpha < .04F) return;
        int x = graphics.guiWidth() - width - 10;
        graphics.fill(x + 1, y + 2, x + width + 1, y + panelHeight + 2,
                LumiTheme.withOpacity(0xff000000, alpha * .18F));
        LumiTheme.outlined(graphics, x, y, width, panelHeight,
                LumiTheme.withOpacity(LumiTheme.WINDOW, alpha * .9F),
                LumiTheme.withOpacity(LumiTheme.WINDOW_BORDER, alpha * .45F));
        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + panelHeight - 1);
        try {
            if (mix < 1) draw(graphics, previous, x, y, width, alpha * (1 - mix), (int) (-4 * mix));
            draw(graphics, current, x, y, width, alpha * mix, (int) (4 * (1 - mix)));
        } finally {
            graphics.disableScissor();
        }
        if (next.saved()) {
            int inset = (int) (5 * (1 - mix));
            int color = LumiTheme.withOpacity(LumiTheme.accent(), alpha * .8F);
            for (int side = 0; side < 2; side++) {
                int edge = side == 0 ? x + inset : x + width - inset - 1;
                graphics.fill(edge, y + inset, edge + 1, y + 8 + inset, color);
                graphics.fill(side == 0 ? edge : edge - 7, y + inset,
                        side == 0 ? edge + 8 : edge + 1, y + inset + 1, color);
            }
        }
    }

    private void draw(GuiGraphics graphics, Content content, int x, int y, int width, float alpha, int shift) {
        if (alpha < .04F) return;
        int color = content.error() ? LumiTheme.DANGER : LumiTheme.accent();
        var font = Minecraft.getInstance().font;
        int detailWidth = content.compact()
                ? Math.min(font.width(content.detail()), Math.max(0, (width - 20) / 2)) : 0;
        text(graphics, content.title(), x + 10 + shift, y + 8,
                width - 20 - (detailWidth > 0 ? detailWidth + 8 : 0), LumiTheme.TEXT, alpha);
        int bottom = y + 24;
        if (content.compact()) {
            text(graphics, content.detail(), x + width - detailWidth - 10 + shift,
                    y + 8, detailWidth, LumiTheme.MUTED, alpha);
        } else if (content.error()) {
            var lines = font.split(Component.literal(content.detail()), width - 20);
            for (int index = 0; index < Math.min(3, lines.size()); index++) {
                graphics.drawString(font, lines.get(index), x + 10 + shift, y + 23 + index * 11,
                        LumiTheme.withOpacity(color, alpha), false);
            }
            bottom = y + 42 + Math.max(0, Math.min(3, lines.size()) - 1) * 11;
        } else {
            text(graphics, content.detail(), x + 10 + shift, y + 23, width - 20, color, alpha);
            bottom = y + 42;
        }
        if (content.busy()) {
            float barAlpha = alpha * Math.clamp((System.nanoTime() - entered - 450_000_000L) / 300_000_000F, 0, 1);
            int barWidth = width - 20;
            graphics.fill(x + 10, bottom, x + width - 10, bottom + 2, LumiTheme.withOpacity(LumiTheme.CHIP, barAlpha));
            int segment = Math.max(1, barWidth / 4);
            int offset = content.progress().isPresent() ? 0 : LumiOperationHud.indeterminateOffset(System.currentTimeMillis(), barWidth - segment);
            int length = content.progress().isPresent()
                    ? (int) Math.round(barWidth * content.progress().getAsDouble()) : segment;
            graphics.fill(x + 10 + offset, bottom, x + 10 + offset + length, bottom + 2,
                    LumiTheme.withOpacity(color, barAlpha));
            bottom += 8;
        }
        for (String shortcut : content.shortcuts()) {
            text(graphics, shortcut, x + 10, bottom + 4, width - 20, LumiTheme.MUTED, alpha);
            bottom += 13;
        }
    }

    private static int detailLines(Content content, int width) {
        return content.error() ? Math.min(3, Minecraft.getInstance().font.split(
                Component.literal(content.detail()), width - 20).size()) : 1;
    }

    static int contentHeight(boolean compact, int detailLines, boolean busy, int shortcuts) {
        return (compact ? 24 : 42 + (Math.clamp(detailLines, 1, 3) - 1) * 11)
                + (busy ? 8 : 0) + Math.clamp(shortcuts, 0, 3) * 13;
    }

    private static void text(GuiGraphics graphics, String text, int x, int y, int width, int color, float alpha) {
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, font.plainSubstrByWidth(text, Math.max(0, width)), x, y,
                LumiTheme.withOpacity(color, alpha), false);
    }
}
