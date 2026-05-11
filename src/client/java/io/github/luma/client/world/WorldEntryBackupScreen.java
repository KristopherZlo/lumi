package io.github.luma.client.world;

import io.github.luma.minecraft.bootstrap.WorldInitialBackupProgress;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

final class WorldEntryBackupScreen extends Screen {

    private static final int TITLE_TEXT_COLOR = 0x55D6FF;
    private static final int BODY_TEXT_COLOR = 0xFFFFFF;
    private static final int XP_BAR_WIDTH = 182;
    private static final int XP_BAR_HEIGHT = 5;
    private static final Identifier XP_BAR_BACKGROUND =
            Identifier.withDefaultNamespace("hud/experience_bar_background");
    private static final Identifier XP_BAR_PROGRESS =
            Identifier.withDefaultNamespace("hud/experience_bar_progress");

    private final Runnable onAccepted;
    private final Runnable onFailedBack;
    private StringWidget titleText;
    private MultiLineTextWidget messageText;
    private MultiLineTextWidget statusText;
    private StringWidget progressText;
    private Button actionButton;
    private boolean running;
    private boolean failed;
    private boolean opening;
    private boolean progressVisible;
    private int progressBarX;
    private int progressBarY;
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
        int contentWidth = Math.min(420, this.width - 40);
        int centerX = this.width / 2;
        int y = Math.max(34, this.height / 2 - 96);

        this.titleText = new StringWidget(this.title.copy().withColor(TITLE_TEXT_COLOR), this.font);
        this.titleText.setX(centerX - this.titleText.getWidth() / 2);
        this.titleText.setY(y);
        this.addRenderableWidget(this.titleText);

        y += 28;
        this.messageText = new MultiLineTextWidget(Component.empty(), this.font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        this.messageText.setY(y);
        this.addRenderableWidget(this.messageText);

        this.statusText = new MultiLineTextWidget(Component.empty(), this.font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        this.statusText.visible = false;
        this.statusText.setY(y);
        this.addRenderableWidget(this.statusText);

        this.progressText = new StringWidget(Component.empty(), this.font);
        this.progressText.visible = false;
        this.addRenderableWidget(this.progressText);

        int buttonWidth = 120;
        this.actionButton = Button.builder(Component.translatable("luma.alpha_warning.accept"), button -> this.accept())
                .bounds((this.width - buttonWidth) / 2, this.height / 2 + 70, buttonWidth, 20)
                .build();
        this.addRenderableWidget(this.actionButton);
        this.updateViewState();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderStaticBackground(graphics);
        if (this.progressVisible) {
            this.renderExperienceProgress(
                    graphics,
                    this.progressBarX,
                    this.progressBarY,
                    this.opening ? 1.0D : this.progress.fraction()
            );
        }
        super.render(graphics, mouseX, mouseY, partialTick);
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
        this.updateViewState();
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
        this.updateViewState();
    }

    void fail(Component failureMessage) {
        this.failed = true;
        this.running = false;
        this.opening = false;
        this.failureMessage = failureMessage == null
                ? Component.translatable("luma.alpha_warning.backup_failed")
                : failureMessage;
        this.updateViewState();
    }

    private void accept() {
        if (this.failed) {
            this.onFailedBack.run();
            return;
        }
        this.running = true;
        this.updateViewState();
        this.onAccepted.run();
    }

    private void updateViewState() {
        if (this.actionButton != null) {
            this.actionButton.setMessage(this.failed
                    ? Component.translatable("gui.back")
                    : Component.translatable("luma.alpha_warning.accept"));
            this.actionButton.active = !this.running && !this.opening;
        }
        this.updateTextState();
    }

    private void updateTextState() {
        if (this.messageText == null || this.statusText == null || this.progressText == null) {
            return;
        }

        int contentWidth = Math.min(420, this.width - 40);
        int centerX = this.width / 2;
        int y = Math.max(34, this.height / 2 - 96);
        if (this.titleText != null) {
            this.titleText.setX(centerX - this.titleText.getWidth() / 2);
            this.titleText.setY(y);
        }
        y += 28;

        this.messageText.visible = false;
        this.statusText.visible = false;
        this.progressText.visible = false;
        this.progressVisible = false;

        if (this.failed) {
            this.statusText.setMessage(this.failureMessage.copy().withColor(BODY_TEXT_COLOR));
            this.statusText.visible = true;
            this.layoutMultilineText(this.statusText, centerX, y, contentWidth);
            return;
        }

        if (this.running || this.opening) {
            this.statusText.setMessage(Component.translatable(this.opening
                    ? "luma.alpha_warning.opening_world"
                    : "luma.alpha_warning.backup_loading").withColor(BODY_TEXT_COLOR));
            this.statusText.visible = true;
            y = this.layoutMultilineText(this.statusText, centerX, y, contentWidth) + 10;
            this.progressBarX = centerX - XP_BAR_WIDTH / 2;
            this.progressBarY = y;
            this.progressVisible = true;
            y += 14;
            this.progressText.setMessage(Component.translatable(
                    "luma.alpha_warning.backup_progress",
                    this.progress.completedChunks(),
                    this.progress.totalChunks(),
                    this.progress.backedUpChunks()
            ).withColor(BODY_TEXT_COLOR));
            this.progressText.visible = true;
            this.progressText.setX(centerX - this.progressText.getWidth() / 2);
            this.progressText.setY(y);
            return;
        }

        this.messageText.setMessage(Component.translatable("luma.alpha_warning.message").withColor(BODY_TEXT_COLOR));
        this.messageText.visible = true;
        this.layoutMultilineText(this.messageText, centerX, y, contentWidth);
    }

    private int layoutMultilineText(MultiLineTextWidget text, int centerX, int y, int width) {
        text.setMaxWidth(width).setCentered(true);
        text.setX(centerX - text.getWidth() / 2);
        text.setY(y);
        return y + text.getHeight();
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
