package io.github.luma.gbreak.client;

import io.github.luma.gbreak.client.ui.screen.CorruptionSettingsScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
final class CorruptionSettingsClientRuntime {

    private final KeyBinding openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.gbreakdev.open_corrupt_settings",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            KeyBinding.Category.MISC
    ));

    void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("corruptsettings")
                        .executes(context -> this.openSettings(context.getSource().getClient()))
        ));
    }

    private void endTick(MinecraftClient client) {
        while (this.openSettingsKey.wasPressed()) {
            this.openSettings(client);
        }
    }

    private int openSettings(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return 0;
        }

        client.setScreen(new CorruptionSettingsScreen());
        return 1;
    }
}
