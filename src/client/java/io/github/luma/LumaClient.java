package io.github.luma;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import io.github.luma.ui.screen.DashboardScreen;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import org.lwjgl.glfw.GLFW;

public final class LumaClient implements ClientModInitializer {

    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "general")
    );
    private static final String OPEN_DASHBOARD_KEY = "key.luma.open_dashboard";

    private KeyMapping openDashboardKey;

    @Override
    public void onInitializeClient() {
        this.openDashboardKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                OPEN_DASHBOARD_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(CompareOverlayRenderer::render);
    }

    private void onEndTick(Minecraft client) {
        while (this.openDashboardKey.consumeClick()) {
            if (client.player == null) {
                continue;
            }

            client.setScreen(new DashboardScreen(client.screen));
        }
    }
}
