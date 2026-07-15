package io.github.lumi.minecraft.runtime;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.service.SaveRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Temporary server-authoritative command surface for exercising V2 vertical slices. */
public final class LumiCommands {
    private LumiCommands() { }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                dispatcher.register(literal("lumi")
                        .then(literal("save")
                                .requires(LumiCommands::mayUse)
                                .executes(command -> save(command.getSource(), "Save"))
                                .then(argument("message", greedyString()).executes(command ->
                                        save(command.getSource(), getString(command, "message")))))));
    }

    private static boolean mayUse(CommandSourceStack source) {
        var player = source.getPlayer();
        return player == null || source.getServer().getPlayerList().isOp(player.nameAndId());
    }

    private static int save(CommandSourceStack source, String message) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Lumi is not ready for this dimension"));
            return 0;
        }
        var player = source.getPlayer();
        CommitAuthor author = player == null
                ? new CommitAuthor(new UUID(0, 0), source.getTextName())
                : new CommitAuthor(player.getUUID(), source.getTextName());
        try {
            runtime.startSave(new SaveRequest(
                    runtime.mainRef(), author, message, Instant.now(),
                    runtime.defaultWorkspaceId(), Optional.empty(), CommitKind.MANUAL));
            source.sendSuccess(() -> Component.literal("Lumi save started"), false);
            return 1;
        } catch (IOException | IllegalStateException failed) {
            source.sendFailure(Component.literal("Lumi save could not start: " + failed.getMessage()));
            return 0;
        }
    }
}
