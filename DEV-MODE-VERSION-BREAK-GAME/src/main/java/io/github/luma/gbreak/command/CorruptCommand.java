package io.github.luma.gbreak.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.luma.gbreak.server.WorldCorruptionService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class CorruptCommand {

    private final WorldCorruptionService worldCorruptionService;

    public CorruptCommand(WorldCorruptionService worldCorruptionService) {
        this.worldCorruptionService = worldCorruptionService;
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("corrupt")
                        .then(CommandManager.literal("on")
                                .executes(context -> this.enable(context.getSource())))
                        .then(CommandManager.literal("off")
                                .executes(context -> this.disable(context.getSource())))
                        .executes(context -> this.status(context.getSource()))
        ));
    }

    private int enable(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WorldCorruptionService.StartResult result = this.worldCorruptionService.start(player);
        if (!result.started()) {
            source.sendFeedback(() -> Text.literal("World corruption is already running or restoring."), false);
            return 0;
        }

        source.sendFeedback(() -> Text.literal("World corruption enabled."), false);
        return 1;
    }

    private int disable(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WorldCorruptionService.StopResult result = this.worldCorruptionService.stop(player);
        if (!result.wasRunning()) {
            source.sendFeedback(() -> Text.literal("World corruption is already disabled."), false);
            return 0;
        }

        source.sendFeedback(() -> Text.literal("World corruption restore queued: "
                + result.restoreQueueSize()
                + " blocks, removed "
                + result.removedDisplays()
                + " sky displays."), false);
        return 1;
    }

    private int status(ServerCommandSource source) {
        WorldCorruptionService.StatusSnapshot status = this.worldCorruptionService.status();
        source.sendFeedback(() -> Text.literal("World corruption: "
                + (status.corrupting() ? "on" : status.restoring() ? "restoring" : "off")
                + " | tracked blocks: " + status.trackedBlocks()
                + " | restore queue: " + status.restoreQueueSize()
                + " | sky displays: " + status.activeDisplays()), false);
        return 1;
    }
}
