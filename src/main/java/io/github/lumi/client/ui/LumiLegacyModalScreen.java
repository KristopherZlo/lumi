package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.Window;
import io.github.lumi.LumiMod;
import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.client.onboarding.ClientContextualHelpService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Shared legacy window chrome for V2 modal workflows. */
abstract class LumiLegacyModalScreen extends Screen {
    private static final Identifier HINT_CLOSE_ICON = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "textures/gui/icons/close.png");
    private final Screen background;
    private final ClientContextualHelpService contextualHelp =
            new ClientContextualHelpService();
    private LumiUiScale uiScale = LumiUiScale.forFramebuffer(1280, 720);
    private ClientContextualHelpHint contextualHint;
    private int hintX;
    private int hintY;
    private int hintWidth;
    private int hintHeight;

    protected LumiLegacyModalScreen(Component title) {
        this(Minecraft.getInstance().screen, title);
    }

    protected LumiLegacyModalScreen(Screen background, Component title) {
        super(title);
        this.background = background;
    }

    protected final LumiLegacyButton addLegacyButton(
            int x, int y, int width, Component label,
            Runnable action, LumiLegacyButton.Kind kind) {
        return addRenderableWidget(new LumiLegacyButton(
                x, y, width, 20, label, ignored -> action.run(), kind));
    }

    protected final LumiLegacyButton addLegacyIconButton(
            int x, int y, String icon, Component label,
            Runnable action, LumiLegacyButton.Kind kind) {
        return addRenderableWidget(new LumiLegacyButton(
                x, y, 26, 20, label, ignored -> action.run(), kind, icon));
    }

    protected final void beginLegacyInit() {
        contextualHint = null;
        Window window = Minecraft.getInstance().getWindow();
        int currentGuiScale = currentGuiScale();
        uiScale = LumiUiScale.current();
        width = uiScale.virtualSize(window.getGuiScaledWidth(), currentGuiScale);
        height = uiScale.virtualSize(window.getGuiScaledHeight(), currentGuiScale);
    }

    protected final boolean addContextualHint(
            ClientContextualHelpHint hint, int x, int y, int width) {
        if (!contextualHelp.shouldShowHint(hint)) {
            return false;
        }
        contextualHint = hint;
        hintX = x;
        hintY = y;
        hintWidth = width;
        hintHeight = 28 + font.split(
                Component.translatable(hint.bodyKey()), Math.max(1, width - 14)).size() * 10;
        return true;
    }

    protected final void resetContextualHints() {
        contextualHelp.resetHints();
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (contextualHint != null) {
            LegacyLumiTheme.outlined(
                    graphics, hintX, hintY, hintWidth, hintHeight,
                    LegacyLumiTheme.STATUS, LegacyLumiTheme.STATUS_BORDER);
            String title = font.plainSubstrByWidth(
                    Component.translatable(contextualHint.titleKey()).getString(),
                    Math.max(1, hintWidth - 44));
            graphics.drawString(font, title, hintX + 7, hintY + 7,
                    LegacyLumiTheme.ACCENT, false);
            int lineY = hintY + 21;
            for (var line : font.split(
                    Component.translatable(contextualHint.bodyKey()),
                    Math.max(1, hintWidth - 14))) {
                graphics.drawString(font, line, hintX + 7, lineY,
                        LegacyLumiTheme.TEXT, false);
                lineY += 10;
            }
            int closeX = hintX + hintWidth - 28;
            boolean hovered = mouseX >= closeX && mouseX < closeX + 22
                    && mouseY >= hintY + 4 && mouseY < hintY + 22;
            LegacyLumiTheme.outlined(
                    graphics, closeX, hintY + 4, 22, 18,
                    hovered ? LegacyLumiTheme.CHIP : LegacyLumiTheme.INSET,
                    LegacyLumiTheme.STATUS_BORDER);
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, HINT_CLOSE_ICON,
                    closeX + 5, hintY + 7, 0, 0, 12, 12,
                    24, 24, 24, 24);
        }
    }

    protected final LegacyRenderContext beginLegacyRender(
            GuiGraphics graphics, int mouseX, int mouseY) {
        if (background != null && background != this) {
            background.render(graphics, -1, -1, 0.0F);
        }
        float scale = renderScale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        return new LegacyRenderContext(
                virtualCoordinate(mouseX), virtualCoordinate(mouseY));
    }

    protected final void endLegacyRender(GuiGraphics graphics) {
        graphics.pose().popMatrix();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        MouseButtonEvent virtual = virtualClick(click);
        if (contextualHint != null
                && virtual.x() >= hintX && virtual.x() < hintX + hintWidth
                && virtual.y() >= hintY && virtual.y() < hintY + hintHeight) {
            int closeX = hintX + hintWidth - 28;
            if (virtual.x() >= closeX && virtual.x() < closeX + 22
                    && virtual.y() >= hintY + 4 && virtual.y() < hintY + 22) {
                contextualHelp.dismissHint(contextualHint);
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseClicked(virtual, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return super.mouseReleased(virtualClick(click));
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return super.mouseScrolled(
                virtualCoordinate(mouseX), virtualCoordinate(mouseY),
                horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        float scale = renderScale();
        return super.mouseDragged(virtualClick(click), deltaX / scale, deltaY / scale);
    }

    protected final void renderLegacyWindow(
            GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(0, 0, this.width, this.height, LegacyLumiTheme.BACKDROP);
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.WINDOW_BORDER);
    }

    protected final void renderLegacyPage(
            GuiGraphics graphics, int x, int y, int width, int height) {
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.WINDOW_BORDER);
    }

    protected final void renderLegacyPanel(
            GuiGraphics graphics, int x, int y, int width, int height) {
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
    }

    protected static Component errorText(String error) {
        return error.startsWith("luma.")
                ? Component.translatable(error) : Component.literal(error);
    }

    private MouseButtonEvent virtualClick(MouseButtonEvent click) {
        return new MouseButtonEvent(
                virtualCoordinate(click.x()), virtualCoordinate(click.y()),
                click.buttonInfo());
    }

    private int virtualCoordinate(int coordinate) {
        return (int) Math.round(virtualCoordinate((double) coordinate));
    }

    protected final double virtualCoordinate(double coordinate) {
        return uiScale.virtualCoordinate(coordinate, currentGuiScale());
    }

    private float renderScale() {
        return uiScale.renderScale(currentGuiScale());
    }

    private static int currentGuiScale() {
        Window window = Minecraft.getInstance().getWindow();
        return window == null ? 1 : window.getGuiScale();
    }

    protected record LegacyRenderContext(int mouseX, int mouseY) { }
}
