package io.github.luma.gbreak.client;

import io.github.luma.gbreak.client.ui.screen.LumiDemoProjectScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
final class LumiDemoClientRuntime {

    private final KeyBinding openProjectScreenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.gbreakdev.open_lumi_demo",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            KeyBinding.Category.MISC
    ));

    void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("gbreakui")
                        .executes(context -> this.openProjectScreen(context.getSource().getClient()))
        ));
    }

    private void endTick(MinecraftClient client) {
        while (this.openProjectScreenKey.wasPressed()) {
            this.openProjectScreen(client);
        }
    }

    private int openProjectScreen(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return 0;
        }

        client.setScreen(new LumiDemoProjectScreen());
        client.player.sendMessage(Text.translatable("gbreakdev.ui.opened"), true);
        return 1;
    }
}
