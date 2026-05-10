package io.github.luma.client.world;

import io.github.luma.minecraft.bootstrap.WorldInitialBackupProgress;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

final class WorldEntryBackupScreen extends Screen {

    private static final int MESSAGE_COLOR = 0x55D6FF;
    private static final int ERROR_COLOR = 0xFF5555;
    private static final int TEXT_COLOR = 0xE8EEF8;
    private static final int XP_BAR_WIDTH = 182;
    private static final int XP_BAR_HEIGHT = 5;
    private static final Identifier XP_BAR_BACKGROUND =
            Identifier.withDefaultNamespace("hud/experience_bar_background");
    private static final Identifier XP_BAR_PROGRESS =
            Identifier.withDefaultNamespace("hud/experience_bar_progress");

    private final Runnable onAccepted;
    private final Runnable onFailedBack;
    private Button actionButton;
    private boolean running;
    private boolean failed;
    private boolean opening;
    private Component failureMessage = Component.empty();
    private WorldInitialBackupProgress progress = new WorldInitialBackupProgress(0, 1, 0, 0L, "");

    WorldEntryBackupScreen(Runnable onAccepted, Runnable onFailedBack) {
        super(Component.translatable("luma.alpha_warning.title"));
        this.onAccepted = onAccepted == null ? () -> {
        } : onAccepted;
        this.onFailedBack = onFailedBack == null ? () -> {
        } : onFailedBack;
    }

    @Override
    protected void init() {
        int buttonWidth = 120;
        this.actionButton = Button.builder(Component.literal("Got it!"), button -> this.accept())
                .bounds((this.width - buttonWidth) / 2, this.height / 2 + 70, buttonWidth, 20)
                .build();
        this.addRenderableWidget(this.actionButton);
        this.updateButtonState();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderStaticBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int contentWidth = Math.min(420, this.width - 40);
        int y = Math.max(34, this.height / 2 - 96);

        graphics.drawCenteredString(this.font, this.title, centerX, y, TEXT_COLOR);
        y += 28;

        if (this.failed) {
            y = this.drawWrappedCentered(graphics, this.failureMessage, centerX, y, contentWidth, ERROR_COLOR);
        } else if (this.running || this.opening) {
            y = this.drawWrappedCentered(
                    graphics,
                    Component.translatable(this.opening
                            ? "luma.alpha_warning.opening_world"
                            : "luma.alpha_warning.backup_loading"),
                    centerX,
                    y,
                    contentWidth,
                    TEXT_COLOR
            );
            y += 10;
            this.renderExperienceProgress(graphics, centerX - XP_BAR_WIDTH / 2, y, this.opening ? 1.0D : this.progress.fraction());
            y += 14;
            graphics.drawCenteredString(
                    this.font,
                    Component.translatable(
                            "luma.alpha_warning.backup_progress",
                            this.progress.completedChunks(),
                            this.progress.totalChunks(),
                            this.progress.backedUpChunks()
                    ),
                    centerX,
                    y,
                    TEXT_COLOR
            );
        } else {
            this.drawWrappedCentered(
                    graphics,
                    Component.translatable("luma.alpha_warning.message"),
                    centerX,
                    y,
                    contentWidth,
                    MESSAGE_COLOR
            );
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    void updateProgress(WorldInitialBackupProgress progress) {
        if (progress != null) {
            this.progress = progress;
        }
        this.running = true;
        this.failed = false;
        this.updateButtonState();
    }

    void markOpening() {
        this.opening = true;
        this.running = false;
        this.failed = false;
        this.progress = new WorldInitialBackupProgress(
                this.progress.totalChunks(),
                this.progress.totalChunks(),
                this.progress.backedUpChunks(),
                this.progress.compressedBytes(),
                this.progress.currentDimensionId()
        );
        this.updateButtonState();
    }

    void fail(Component failureMessage) {
        this.failed = true;
        this.running = false;
        this.opening = false;
        this.failureMessage = failureMessage == null
                ? Component.translatable("luma.alpha_warning.backup_failed")
                : failureMessage;
        this.updateButtonState();
    }

    private void accept() {
        if (this.failed) {
            this.onFailedBack.run();
            return;
        }
        this.running = true;
        this.updateButtonState();
        this.onAccepted.run();
    }

    private void updateButtonState() {
        if (this.actionButton == null) {
            return;
        }
        this.actionButton.setMessage(this.failed ? Component.translatable("gui.back") : Component.literal("Got it!"));
        this.actionButton.active = !this.running && !this.opening;
    }

    private int drawWrappedCentered(
            GuiGraphics graphics,
            Component text,
            int centerX,
            int y,
            int width,
            int color
    ) {
        List<FormattedCharSequence> lines = this.font.split(text, width);
        for (FormattedCharSequence line : lines) {
            graphics.drawString(this.font, line, centerX - this.font.width(line) / 2, y, color);
            y += 11;
        }
        return y;
    }

    private void renderStaticBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, 0xE0101010);
    }

    private void renderExperienceProgress(GuiGraphics graphics, int x, int y, double fraction) {
        int filled = Math.max(0, Math.min(XP_BAR_WIDTH, (int) Math.round(XP_BAR_WIDTH * fraction)));
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, XP_BAR_BACKGROUND, x, y, XP_BAR_WIDTH, XP_BAR_HEIGHT);
        if (filled > 0) {
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    XP_BAR_PROGRESS,
                    XP_BAR_WIDTH,
                    XP_BAR_HEIGHT,
                    0,
                    0,
                    x,
                    y,
                    filled,
                    XP_BAR_HEIGHT
            );
        }
    }
}
