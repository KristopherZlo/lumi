package io.github.luma.mixin.client;

import io.github.luma.LumaMod;
import io.github.luma.client.world.LumiBackupRestoreConfirmScreen;
import io.github.luma.minecraft.bootstrap.WorldInitialBackupRestoreService;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EditWorldScreen.class)
abstract class EditWorldScreenMixin extends Screen {

    @Shadow
    @Final
    private LinearLayout layout;

    protected EditWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/LinearLayout;visitWidgets(Ljava/util/function/Consumer;)V"
            )
    )
    private void luma$addRestoreFromBackupButton(
            Minecraft minecraft,
            LevelStorageAccess levelAccess,
            String levelName,
            BooleanConsumer callback,
            CallbackInfo info
    ) {
        Button button = Button.builder(
                        Component.translatable("luma.backup_restore.edit_button"),
                        ignored -> minecraft.setScreen(new LumiBackupRestoreConfirmScreen(
                                (Screen) (Object) this,
                                levelAccess,
                                callback
                        ))
                )
                .width(200)
                .build();
        button.active = this.hasRestorableBackup(levelAccess);
        this.layout.addChild(button);
    }

    private boolean hasRestorableBackup(LevelStorageAccess levelAccess) {
        try {
            return new WorldInitialBackupRestoreService().hasRestorableBackup(levelAccess.getLevelPath(LevelResource.ROOT));
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to inspect Lumi pre-mod backup for {}", levelAccess.getLevelId(), exception);
            return false;
        }
    }
}
