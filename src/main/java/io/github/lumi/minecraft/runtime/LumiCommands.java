package io.github.lumi.minecraft.runtime;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.domain.service.PermissionDecision;
import io.github.lumi.domain.service.RecoveryChoice;
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
            var restoreWithoutEntities = literal("restore-no-entities")
                    .requires(LumiCommands::mayUse)
                    .then(argument("commit", word()).executes(command ->
                            restoreWithoutEntities(
                                    command.getSource(), getString(command, "commit"))));
            var rollback = literal("rollback")
                    .requires(LumiCommands::mayUse)
                    .executes(command -> quickRollback(command.getSource()));
            var recover = literal("recover")
                    .requires(LumiCommands::mayUse)
                    .then(literal("resume").executes(command -> recovery(
                            command.getSource(), RecoveryChoice.RESUME_TARGET)))
                    .then(literal("return").executes(command -> recovery(
                            command.getSource(), RecoveryChoice.RETURN_CHECKPOINT)));
            var undo = literal("undo").requires(LumiCommands::mayUse)
                    .executes(command -> liveAction(command.getSource(), LiveActionJournal.Direction.UNDO));
            var redo = literal("redo").requires(LumiCommands::mayUse)
                    .executes(command -> liveAction(command.getSource(), LiveActionJournal.Direction.REDO));
            var branch = literal("branch")
                    .requires(LumiCommands::mayUse)
                    .then(literal("create")
                            .then(argument("name", greedyString()).executes(command ->
                                    createBranch(command.getSource(), getString(command, "name")))))
                    .then(literal("switch")
                            .then(argument("name", greedyString()).executes(command ->
                                    switchBranch(command.getSource(), getString(command, "name")))));
            var survival = literal("survival")
                    .requires(LumiCommands::mayConfigure)
                    .then(argument("enabled", bool()).executes(command ->
                            configureSurvival(command.getSource(), getBool(command, "enabled"))));
            var outsideArg = argument("outside", bool()).executes(command ->
                    restoreArea(command.getSource(), getString(command, "commit"),
                            new BlockBox(
                                    getInteger(command, "x1"), getInteger(command, "y1"),
                                    getInteger(command, "z1"), getInteger(command, "x2"),
                                    getInteger(command, "y2"), getInteger(command, "z2")),
                            getBool(command, "outside")));
            var z2Arg = argument("z2", integer()).then(outsideArg);
            var y2Arg = argument("y2", integer()).then(z2Arg);
            var x2Arg = argument("x2", integer()).then(y2Arg);
            var z1Arg = argument("z1", integer()).then(x2Arg);
            var y1Arg = argument("y1", integer()).then(z1Arg);
            var x1Arg = argument("x1", integer()).then(y1Arg);
            var restoreArea = literal("restore-area")
                    .requires(LumiCommands::mayUse)
                    .then(argument("commit", word()).then(x1Arg));
            dispatcher.register(literal("lumi").then(save).then(restore)
                    .then(restoreWithoutEntities).then(restoreArea)
                    .then(rollback).then(undo).then(redo)
                    .then(recover).then(branch).then(survival));
        });
    }

    private static boolean mayUse(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null) {
            return true;
        }
        try {
            return LumiMod.serverRuntime().permission(player) == PermissionDecision.ALLOWED;
        } catch (IOException unavailable) {
            LumiMod.LOGGER.error("Cannot read Lumi permissions", unavailable);
            return false;
        }
    }

    private static boolean mayConfigure(CommandSourceStack source) {
        var player = source.getPlayer();
        return player == null || LumiMod.serverRuntime().mayConfigure(player);
    }

    private static int configureSurvival(CommandSourceStack source, boolean enabled) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("A player must choose their own Survival setting"));
            return 0;
        }
        try {
            LumiMod.serverRuntime().setSurvivalEnabled(player, enabled);
            source.sendSuccess(() -> Component.literal(
                    "Lumi in Survival is now " + (enabled ? "enabled" : "disabled")), false);
            return 1;
        } catch (IOException | SecurityException failed) {
            source.sendFailure(Component.literal("Cannot change Lumi Survival access: "
                    + failed.getMessage()));
            return 0;
        }
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
                    runtime.activeWorkspaceId(), Optional.empty(), CommitKind.MANUAL));
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

    private static int restoreWithoutEntities(CommandSourceStack source, String commitHex) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Lumi is not ready for this dimension"));
            return 0;
        }
        try {
            runtime.startRestore(
                    new CommitId(new ObjectId(commitHex)), author(source), false, ignored -> { });
            source.sendSuccess(() -> Component.literal(
                    "Lumi Restore without entities started"), false);
            return 1;
        } catch (IOException | IllegalStateException | IllegalArgumentException failed) {
            source.sendFailure(Component.literal(
                    "Lumi Restore without entities could not start: " + failed.getMessage()));
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
            runtime.startBranchSwitch(runtime.visibleBranchName(new BranchName(name)));
            source.sendSuccess(() -> Component.literal("Lumi branch switch started: " + name), false);
            return 1;
        } catch (IOException | IllegalStateException | IllegalArgumentException failed) {
            source.sendFailure(Component.literal("Lumi branch switch could not start: "
                    + failed.getMessage()));
            return 0;
        }
    }

    private static int restoreArea(
            CommandSourceStack source, String commitHex, BlockBox area, boolean outside) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Lumi is not ready for this dimension"));
            return 0;
        }
        try {
            runtime.startPartialRestore(
                    new CommitId(new ObjectId(commitHex)),
                    new BlockAreaTarget(area, outside), author(source), ignored -> { });
            source.sendSuccess(() -> Component.literal(
                    "Lumi block-area Restore started"), false);
            return 1;
        } catch (IOException | IllegalStateException | IllegalArgumentException failed) {
            source.sendFailure(Component.literal("Lumi block-area Restore could not start: "
                    + failed.getMessage()));
            return 0;
        }
    }

    private static int quickRollback(CommandSourceStack source) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Lumi is not ready for this dimension"));
            return 0;
        }
        try {
            runtime.startQuickRollback(author(source), operation ->
                    operation.completionMessage().ifPresent(message ->
                            source.sendSuccess(() -> message.startsWith("luma.")
                                    ? Component.translatable(message)
                                    : Component.literal(message), false)));
            source.sendSuccess(() -> Component.literal("Lumi Quick Rollback started"), false);
            return 1;
        } catch (IOException | IllegalStateException failed) {
            source.sendFailure(Component.literal("Lumi Quick Rollback could not start: "
                    + failed.getMessage()));
            return 0;
        }
    }

    private static int recovery(CommandSourceStack source, RecoveryChoice choice) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Lumi recovery requires a ready dimension"));
            return 0;
        }
        try {
            runtime.startRecovery(choice, ignored -> { });
            source.sendSuccess(() -> Component.literal(
                    choice == RecoveryChoice.RESUME_TARGET
                            ? "Lumi recovery is resuming the target"
                            : "Lumi recovery is returning to the checkpoint"), false);
            return 1;
        } catch (IOException | IllegalStateException failed) {
            source.sendFailure(Component.literal(
                    "Lumi recovery could not start: " + failed.getMessage()));
            return 0;
        }
    }

    private static int liveAction(
            CommandSourceStack source, LiveActionJournal.Direction direction) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            source.sendFailure(Component.literal("Lumi live action requires a ready dimension"));
            return 0;
        }
        try {
            runtime.startLiveAction(author(source).id(), direction, ignored -> { });
            source.sendSuccess(() -> Component.literal("Lumi " + direction + " started"), false);
            return 1;
        } catch (IllegalStateException failed) {
            source.sendFailure(Component.literal("Lumi " + direction + " could not start: "
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
