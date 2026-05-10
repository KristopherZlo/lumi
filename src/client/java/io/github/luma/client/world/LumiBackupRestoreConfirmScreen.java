package io.github.luma.client.world;

import io.github.luma.LumaMod;
import io.github.luma.minecraft.bootstrap.WorldInitialBackupRestoreService;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;

public final class LumiBackupRestoreConfirmScreen extends Screen {

    private static final int TITLE_TEXT_COLOR = 0xFF5555;
    private static final int BODY_TEXT_COLOR = 0xFFFFFF;

    private final Screen parent;
    private final LevelStorageAccess levelAccess;
    private final BooleanConsumer callback;
    private final WorldInitialBackupRestoreService restoreService = new WorldInitialBackupRestoreService();

    private MultiLineTextWidget statusText;
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
        int y = Math.max(28, this.height / 2 - 118);

        StringWidget titleText = new StringWidget(this.title.copy().withColor(TITLE_TEXT_COLOR), this.font);
        titleText.setX(centerX - titleText.getWidth() / 2);
        titleText.setY(y);
        this.addRenderableWidget(titleText);

        y += 26;
        MultiLineTextWidget warningText = new MultiLineTextWidget(
                Component.translatable("luma.backup_restore.warning").withColor(BODY_TEXT_COLOR),
                this.font
        ).setMaxWidth(contentWidth).setCentered(true);
        warningText.setX(centerX - warningText.getWidth() / 2);
        warningText.setY(y);
        this.addRenderableWidget(warningText);

        y += warningText.getHeight() + 10;
        this.statusText = new MultiLineTextWidget(Component.empty(), this.font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        this.statusText.visible = false;
        this.statusText.setY(y);
        this.addRenderableWidget(this.statusText);

        y = Math.max(46, this.height / 2 + 16);

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
        if (this.statusText != null) {
            if (this.running) {
                this.statusText.setMessage(Component.translatable("luma.backup_restore.running").withColor(BODY_TEXT_COLOR));
                this.statusText.visible = true;
            } else if (!this.failureMessage.getString().isBlank()) {
                this.statusText.setMessage(this.failureMessage.copy().withColor(BODY_TEXT_COLOR));
                this.statusText.visible = true;
            } else {
                this.statusText.setMessage(Component.empty());
                this.statusText.visible = false;
            }
            this.statusText.setX(this.width / 2 - this.statusText.getWidth() / 2);
        }
    }
}
