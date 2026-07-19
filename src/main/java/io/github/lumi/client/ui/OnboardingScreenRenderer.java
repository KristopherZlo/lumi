package io.github.lumi.client.ui;

import io.github.lumi.client.LumiHotkeys;
import io.github.lumi.client.onboarding.OnboardingHoldGate;
import io.github.lumi.client.onboarding.OnboardingTour;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Draws onboarding content while the screen owns navigation and transitions. */
final class OnboardingScreenRenderer {
    void panel(
            GuiGraphics graphics,
            Font font,
            KeyMapping[] mappings,
            OnboardingTour tour,
            OnboardingHoldGate holdGate,
            int x,
            int y,
            int width,
            int height) {
        OnboardingTour.Page page = tour.current();
        boolean compact = height < 224;
        int actionY = y + height - 30;
        int contentY = compact ? y + 8 : y + 36;
        int contentHeight = compact ? height - 44 : 140;
        int textY = compact ? y + 16 : y + 48;
        int shortcutY = compact ? actionY - 38 : y + 126;
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.WINDOW_BORDER);
        LegacyLumiTheme.outlined(
                graphics, x + 12, contentY, width - 24, contentHeight,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
        int textBottom = compact
                ? (page.holdStep() ? shortcutY - 5 : actionY - 6)
                : y + 126;
        pageText(graphics, font, tour, x + 24, textY, width - 48, textBottom);
        if (!page.holdStep()) return;
        shortcut(graphics, font, mappings, page, x + 24, shortcutY);
        int barX = x + 24;
        int barY = compact ? actionY - 10 : y + 162;
        int barWidth = width - 48;
        graphics.fill(
                barX, barY, barX + barWidth, barY + 3,
                LegacyLumiTheme.INSET_BORDER);
        graphics.fill(
                barX, barY,
                barX + (int) Math.round(barWidth * holdGate.progress()),
                barY + 3, LegacyLumiTheme.ACCENT);
    }

    void spotlight(
            GuiGraphics graphics,
            Font font,
            OnboardingTour tour,
            OnboardingSpotlightLayout.Placement placement,
            int screenWidth,
            int screenHeight) {
        var hole = placement.hole();
        graphics.fill(0, 0, screenWidth, hole.y(), LegacyLumiTheme.BACKDROP);
        graphics.fill(
                0, hole.bottom(), screenWidth, screenHeight,
                LegacyLumiTheme.BACKDROP);
        graphics.fill(
                0, hole.y(), hole.x(), hole.bottom(),
                LegacyLumiTheme.BACKDROP);
        graphics.fill(
                hole.right(), hole.y(), screenWidth, hole.bottom(),
                LegacyLumiTheme.BACKDROP);
        outline(graphics, hole, LegacyLumiTheme.ACCENT);
        var prompt = placement.prompt();
        LegacyLumiTheme.outlined(
                graphics,
                prompt.x(), prompt.y(), prompt.width(), prompt.height(),
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.WINDOW_BORDER);
        pageText(
                graphics, font, tour,
                prompt.x() + 10, prompt.y() + 12, prompt.width() - 20,
                prompt.bottom() - 30);
    }

    private static void pageText(
            GuiGraphics graphics,
            Font font,
            OnboardingTour tour,
            int x,
            int y,
            int textWidth,
            int textBottom) {
        graphics.drawString(
                font,
                Component.translatable(
                        "luma.onboarding.header",
                        tour.displayIndex(), OnboardingTour.pageCount()),
                x, y, LegacyLumiTheme.MUTED, false);
        graphics.drawString(
                font, Component.translatable(tour.current().titleKey()),
                x, y + 16, LegacyLumiTheme.ACCENT, false);
        int lineY = y + 36;
        for (var line : font.split(
                Component.translatable(tour.current().helpKey()), textWidth)) {
            if (lineY + 9 > textBottom) break;
            graphics.drawString(
                    font, line, x, lineY, LegacyLumiTheme.TEXT, false);
            lineY += 11;
        }
    }

    private static void shortcut(
            GuiGraphics graphics,
            Font font,
            KeyMapping[] mappings,
            OnboardingTour.Page page,
            int x,
            int y) {
        graphics.drawString(
                font, Component.translatable(holdInstruction(page.kind())),
                x, y, LegacyLumiTheme.MUTED, false);
        boolean unbound = page.bindings().stream().anyMatch(binding ->
                LumiHotkeys.bindingUnbound(mappings, binding));
        String keys = page.bindings().stream()
                .map(binding -> unbound
                        ? Component.translatable(
                                "luma.onboarding.key_unbound").getString()
                        : LumiHotkeys.bindingLabel(mappings, binding))
                .map(key -> "[" + key + "]")
                .reduce((left, right) -> left + " + " + right)
                .orElse("");
        graphics.drawString(
                font, keys, x, y + 15, LegacyLumiTheme.TEXT, false);
    }

    private static String holdInstruction(OnboardingTour.Kind kind) {
        return switch (kind) {
            case HOLD_SAVE -> "luma.onboarding.hold_quick_save";
            case HOLD_DASHBOARD -> "luma.onboarding.hold_open";
            case HOLD_HOTKEYS -> "luma.onboarding.press_info";
            default -> throw new IllegalArgumentException("Not a hold page");
        };
    }

    private static void outline(
            GuiGraphics graphics,
            OnboardingSpotlightLayout.Rect rect,
            int color) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.y() + 1, color);
        graphics.fill(
                rect.x(), rect.bottom() - 1, rect.right(), rect.bottom(), color);
        graphics.fill(rect.x(), rect.y(), rect.x() + 1, rect.bottom(), color);
        graphics.fill(
                rect.right() - 1, rect.y(), rect.right(), rect.bottom(), color);
    }
}
