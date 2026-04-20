package io.github.luma;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.controller.ClientProjectAccess;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.overlay.WorkspaceHudCoordinator;
import io.github.luma.ui.screen.DashboardScreen;
import io.github.luma.ui.screen.ProjectScreen;
import org.lwjgl.glfw.GLFW;

public final class LumaClient implements ClientModInitializer {

    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "general")
    );
    private static final String OPEN_DASHBOARD_KEY = "key.lumi.open_dashboard";

    private KeyMapping openDashboardKey;
    private final ProjectService projectService = new ProjectService();

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
        WorkspaceHudCoordinator.getInstance().registerHud();
    }

    private void onEndTick(Minecraft client) {
        WorkspaceHudCoordinator.getInstance().tick(client);
        while (this.openDashboardKey.consumeClick()) {
            if (client.player == null) {
                continue;
            }

            try {
                var server = ClientProjectAccess.requireSingleplayerServer(client);
                var level = server.getLevel(client.level == null ? net.minecraft.world.level.Level.OVERWORLD : client.level.dimension());
                if (level == null) {
                    level = server.overworld();
                }

                String projectName = this.projectService.ensureWorldProject(level, client.getUser().getName()).name();
                client.setScreen(new ProjectScreen(client.screen, projectName));
            } catch (Exception exception) {
                client.setScreen(new DashboardScreen(client.screen));
            }
        }
    }
}
