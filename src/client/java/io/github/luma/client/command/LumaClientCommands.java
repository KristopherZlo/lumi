package io.github.luma.client.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.luma.ui.controller.ClientWorkspaceOpenService;
import java.util.Objects;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-only commands for UI workflows that cannot be opened from the server command dispatcher.
 */
public final class LumaClientCommands {

    public static final String ONBOARDING_COMMAND = "lumi-onboarding";

    private final ClientWorkspaceOpenService workspaceOpenService;

    public LumaClientCommands(ClientWorkspaceOpenService workspaceOpenService) {
        this.workspaceOpenService = Objects.requireNonNull(workspaceOpenService, "workspaceOpenService");
    }

    public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal(ONBOARDING_COMMAND)
                .executes(context -> this.openOnboarding(context.getSource())));
    }

    private int openOnboarding(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        if (client == null || client.player == null || client.level == null) {
            source.sendError(Component.translatable("luma.command.onboarding_unavailable"));
            return 0;
        }

        source.sendFeedback(Component.translatable("luma.command.onboarding_opening"));
        client.execute(() -> this.workspaceOpenService.openCurrentWorkspaceOnboarding(client, client.screen));
        return 1;
    }
}
