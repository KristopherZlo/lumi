package io.github.luma.client.world;

import io.github.luma.LumaMod;
import io.github.luma.minecraft.bootstrap.WorldInitialBackupRestoreService;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;

public final class LumiBackupRestoreConfirmScreen extends Screen {

    private static final int ERROR_COLOR = 0xFF5555;
    private static final int TEXT_COLOR = 0xE8EEF8;
    private static final int MUTED_COLOR = 0xFFA0A7B2;

    private final Screen parent;
    private final LevelStorageAccess levelAccess;
    private final BooleanConsumer callback;
    private final WorldInitialBackupRestoreService restoreService = new WorldInitialBackupRestoreService();

    private Button restoreButton;
    private Button cancelButton;
    private Checkbox agreement;
    private boolean running;
    private Component failureMessage = Component.empty();

    public LumiBackupRestoreConfirmScreen(Screen parent, LevelStorageAccess levelAccess, BooleanConsumer callback) {
        super(Component.translatable("luma.backup_restore.title"));
        this.parent = parent;
        this.levelAccess = levelAccess;
        this.callback = callback == null ? ignored -> {
        } : callback;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(420, this.width - 40);
        int centerX = this.width / 2;
        int y = Math.max(46, this.height / 2 + 16);

        this.agreement = Checkbox.builder(Component.translatable("luma.backup_restore.agreement"), this.font)
                .pos(centerX - contentWidth / 2, y)
                .maxWidth(contentWidth)
                .onValueChange((checkbox, selected) -> this.updateButtonState())
                .build();
        this.addRenderableWidget(this.agreement);

        y += Math.max(28, this.agreement.getHeight() + 8);
        this.restoreButton = Button.builder(
                        Component.translatable("luma.backup_restore.rollback_button"),
                        button -> this.restore()
                )
                .bounds(centerX - 154, y, 150, 20)
                .build();
        this.cancelButton = Button.builder(
                        Component.translatable("luma.action.cancel"),
                        button -> this.onClose()
                )
                .bounds(centerX + 4, y, 150, 20)
                .build();
        this.addRenderableWidget(this.restoreButton);
        this.addRenderableWidget(this.cancelButton);
        this.updateButtonState();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int contentWidth = Math.min(420, this.width - 40);
        int y = Math.max(28, this.height / 2 - 118);

        graphics.drawCenteredString(this.font, this.title, centerX, y, TEXT_COLOR);
        y += 26;
        y = this.drawWrappedCentered(
                graphics,
                Component.translatable("luma.backup_restore.warning"),
                centerX,
                y,
                contentWidth,
                ERROR_COLOR
        );
        y += 10;
        if (this.running) {
            this.drawWrappedCentered(
                    graphics,
                    Component.translatable("luma.backup_restore.running"),
                    centerX,
                    y,
                    contentWidth,
                    MUTED_COLOR
            );
        } else if (!this.failureMessage.getString().isBlank()) {
            this.drawWrappedCentered(graphics, this.failureMessage, centerX, y, contentWidth, ERROR_COLOR);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    private void restore() {
        if (this.running || this.agreement == null || !this.agreement.selected()) {
            return;
        }
        this.running = true;
        this.failureMessage = Component.empty();
        this.updateButtonState();
        Path worldRoot = this.levelAccess.getLevelPath(LevelResource.ROOT);
        CompletableFuture.supplyAsync(() -> {
            try {
                return this.restoreService.restore(worldRoot);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, Util.backgroundExecutor()).whenComplete((result, throwable) -> Minecraft.getInstance().execute(() -> {
            this.running = false;
            if (throwable != null) {
                LumaMod.LOGGER.warn("Failed to restore world from Lumi pre-mod backup", throwable);
                this.failureMessage = Component.translatable("luma.backup_restore.failed");
                this.updateButtonState();
                return;
            }
            this.showSuccessToast(result);
            this.callback.accept(true);
        }));
    }

    private void showSuccessToast(WorldInitialBackupRestoreService.RestoreResult result) {
        Minecraft.getInstance().getToastManager().addToast(new SystemToast(
                SystemToast.SystemToastId.WORLD_BACKUP,
                Component.translatable("luma.backup_restore.complete"),
                Component.translatable("luma.backup_restore.complete_detail", result.restoredChunks())
        ));
    }

    private void updateButtonState() {
        if (this.restoreButton != null) {
            this.restoreButton.active = !this.running && this.agreement != null && this.agreement.selected();
        }
        if (this.cancelButton != null) {
            this.cancelButton.active = !this.running;
        }
        if (this.agreement != null) {
            this.agreement.active = !this.running;
        }
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
}
