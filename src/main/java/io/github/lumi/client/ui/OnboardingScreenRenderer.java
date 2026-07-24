package io.github.lumi.client.ui;

import io.github.lumi.client.LumiHotkeys;
import io.github.lumi.client.onboarding.OnboardingController;
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
            OnboardingController controller,
            int x,
            int y,
            int width,
            int height) {
        OnboardingTour.Page page = controller.current();
        boolean compact = height < 224;
        int actionY = y + height - 30;
        int contentY = compact ? y + 8 : y + 36;
        int contentHeight = compact ? height - 44 : 140;
        int textY = compact ? y + 16 : y + 48;
        int shortcutY = compact ? actionY - 38 : y + 126;
        LumiTheme.outlined(
                graphics, x, y, width, height,
                LumiTheme.WINDOW, LumiTheme.WINDOW_BORDER);
        LumiTheme.outlined(
                graphics, x + 12, contentY, width - 24, contentHeight,
                LumiTheme.PANEL, LumiTheme.PANEL_BORDER);
        int textBottom = compact
                ? (page.shortcutStep() ? shortcutY - 5 : actionY - 6)
                : y + 126;
        pageText(graphics, font, controller, x + 24, textY, width - 48, textBottom);
        if (!page.shortcutStep()) return;
        shortcut(graphics, font, mappings, page, x + 24, shortcutY);
    }

    void spotlight(
            GuiGraphics graphics,
            Font font,
            OnboardingController controller,
            OnboardingSpotlightLayout.Placement placement,
            int screenWidth,
            int screenHeight) {
        var hole = placement.hole();
        graphics.fill(0, 0, screenWidth, hole.y(), LumiTheme.BACKDROP);
        graphics.fill(
                0, hole.bottom(), screenWidth, screenHeight,
                LumiTheme.BACKDROP);
        graphics.fill(
                0, hole.y(), hole.x(), hole.bottom(),
                LumiTheme.BACKDROP);
        graphics.fill(
                hole.right(), hole.y(), screenWidth, hole.bottom(),
                LumiTheme.BACKDROP);
        outline(graphics, hole, LumiTheme.ACCENT);
        var prompt = placement.prompt();
        LumiTheme.outlined(
                graphics,
                prompt.x(), prompt.y(), prompt.width(), prompt.height(),
                LumiTheme.WINDOW, LumiTheme.WINDOW_BORDER);
        pageText(
                graphics, font, controller,
                prompt.x() + 10, prompt.y() + 12, prompt.width() - 20,
                prompt.bottom() - 30);
    }

    private static void pageText(
            GuiGraphics graphics,
            Font font,
            OnboardingController controller,
            int x,
            int y,
            int textWidth,
            int textBottom) {
        graphics.drawString(
                font,
                Component.translatable(
                        "luma.onboarding.header",
                        controller.displayIndex(), OnboardingTour.pageCount()),
                x, y, LumiTheme.MUTED, false);
        graphics.drawString(
                font, Component.translatable(controller.current().titleKey()),
                x, y + 16, LumiTheme.ACCENT, false);
        int lineY = y + 36;
        for (var line : font.split(
                Component.translatable(controller.current().helpKey()), textWidth)) {
            if (lineY + 9 > textBottom) break;
            graphics.drawString(
                    font, line, x, lineY, LumiTheme.TEXT, false);
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
                font, Component.translatable(shortcutInstruction(page.kind())),
                x, y, LumiTheme.MUTED, false);
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
                font, keys, x, y + 15, LumiTheme.TEXT, false);
    }

    private static String shortcutInstruction(OnboardingTour.Kind kind) {
        return switch (kind) {
            case SHORTCUT_SAVE -> "luma.onboarding.press_quick_save";
            case SHORTCUT_DASHBOARD -> "luma.onboarding.press_open";
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
