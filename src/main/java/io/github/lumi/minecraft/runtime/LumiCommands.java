package io.github.lumi.minecraft.runtime;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
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
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            var save = literal("save")
                    .requires(LumiCommands::mayUse)
                    .executes(command -> save(command.getSource(), "Save"))
                    .then(argument("message", greedyString()).executes(command ->
                            save(command.getSource(), getString(command, "message"))));
            var restore = literal("restore")
                    .requires(LumiCommands::mayUse)
                    .then(argument("commit", word()).executes(command ->
                            restore(command.getSource(), getString(command, "commit"))));
            var branch = literal("branch")
                    .requires(LumiCommands::mayUse)
                    .then(literal("create")
                            .then(argument("name", greedyString()).executes(command ->
                                    createBranch(command.getSource(), getString(command, "name")))))
                    .then(literal("switch")
                            .then(argument("name", greedyString()).executes(command ->
                                    switchBranch(command.getSource(), getString(command, "name")))));
            dispatcher.register(literal("lumi").then(save).then(restore).then(branch));
        });
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
        try {
            runtime.startSave(new SaveRequest(
                    runtime.activeRef(), author(source), message, Instant.now(),
                    runtime.defaultWorkspaceId(), Optional.empty(), CommitKind.MANUAL));
            source.sendSuccess(() -> Component.literal("Lumi save started"), false);
            return 1;
        } catch (IOException | IllegalStateException failed) {
            source.sendFailure(Component.literal("Lumi save could not start: " + failed.getMessage()));
            return 0;
        }
    }

    private static int restore(CommandSourceStack source, String commitHex) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Lumi is not ready for this dimension"));
            return 0;
        }
        try {
            runtime.startRestore(new CommitId(new ObjectId(commitHex)), author(source));
            source.sendSuccess(() -> Component.literal("Lumi restore started"), false);
            return 1;
        } catch (IOException | IllegalStateException | IllegalArgumentException failed) {
            source.sendFailure(Component.literal("Lumi restore could not start: " + failed.getMessage()));
            return 0;
        }
    }

    private static int createBranch(CommandSourceStack source, String name) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Lumi is not ready for this dimension"));
            return 0;
        }
        try {
            runtime.createBranch(new BranchName(name));
            source.sendSuccess(() -> Component.literal("Lumi branch created: " + name), false);
            return 1;
        } catch (IOException | IllegalArgumentException failed) {
            source.sendFailure(Component.literal("Lumi branch could not be created: "
                    + failed.getMessage()));
            return 0;
        }
    }

    private static int switchBranch(CommandSourceStack source, String name) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Lumi is not ready for this dimension"));
            return 0;
        }
        try {
            runtime.startBranchSwitch(new BranchName(name));
            source.sendSuccess(() -> Component.literal("Lumi branch switch started: " + name), false);
            return 1;
        } catch (IOException | IllegalStateException | IllegalArgumentException failed) {
            source.sendFailure(Component.literal("Lumi branch switch could not start: "
                    + failed.getMessage()));
            return 0;
        }
    }

    private static CommitAuthor author(CommandSourceStack source) {
        var player = source.getPlayer();
        return player == null
                ? new CommitAuthor(new UUID(0, 0), source.getTextName())
                : new CommitAuthor(player.getUUID(), source.getTextName());
    }
}
