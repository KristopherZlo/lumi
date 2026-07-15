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
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

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
            var rollback = literal("rollback")
                    .requires(LumiCommands::mayUse)
                    .executes(command -> quickRollback(command.getSource()));
            var undo = literal("undo").requires(LumiCommands::mayUse)
                    .executes(command -> liveAction(command.getSource(), LiveActionJournal.Direction.UNDO));
            var redo = literal("redo").requires(LumiCommands::mayUse)
                    .executes(command -> liveAction(command.getSource(), LiveActionJournal.Direction.REDO));
            var debugActionSet = literal("debug-action-set")
                    .requires(LumiCommands::mayUse)
                    .then(argument("x", integer()).then(argument("y", integer())
                            .then(argument("z", integer()).then(argument("block", greedyString())
                                    .executes(command -> debugActionSet(
                                            command.getSource(),
                                            new BlockPos(
                                                    getInteger(command, "x"),
                                                    getInteger(command, "y"),
                                                    getInteger(command, "z")),
                                            getString(command, "block")))))));
            var debugActionSummon = literal("debug-action-summon")
                    .requires(LumiCommands::mayUse)
                    .then(argument("x", integer()).then(argument("y", integer())
                            .then(argument("z", integer()).then(argument("entity", greedyString())
                                    .executes(command -> debugActionSummon(
                                            command.getSource(),
                                            new BlockPos(
                                                    getInteger(command, "x"),
                                                    getInteger(command, "y"),
                                                    getInteger(command, "z")),
                                            getString(command, "entity")))))));
            var branch = literal("branch")
                    .requires(LumiCommands::mayUse)
                    .then(literal("create")
                            .then(argument("name", greedyString()).executes(command ->
                                    createBranch(command.getSource(), getString(command, "name")))))
                    .then(literal("switch")
                            .then(argument("name", greedyString()).executes(command ->
                                    switchBranch(command.getSource(), getString(command, "name")))));
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
                    .then(restoreArea).then(rollback).then(undo).then(redo)
                    .then(debugActionSet).then(debugActionSummon).then(branch));
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
            runtime.startQuickRollback(author(source), ignored -> { });
            source.sendSuccess(() -> Component.literal("Lumi Quick Rollback started"), false);
            return 1;
        } catch (IOException | IllegalStateException failed) {
            source.sendFailure(Component.literal("Lumi Quick Rollback could not start: "
                    + failed.getMessage()));
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

    private static int debugActionSet(
            CommandSourceStack source, BlockPos position, String blockName) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        Identifier id = Identifier.tryParse(blockName);
        var block = id == null ? Optional.<Block>empty() : BuiltInRegistries.BLOCK.getOptional(id);
        if (runtime == null || block.isEmpty()) {
            source.sendFailure(Component.literal("Lumi debug action requires a ready dimension and block"));
            return 0;
        }
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), author(source).id())) {
            source.getLevel().setBlock(position, block.orElseThrow().defaultBlockState(), Block.UPDATE_ALL);
        } catch (IllegalStateException failed) {
            source.sendFailure(Component.literal("Lumi debug action failed: " + failed.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Lumi debug block action captured"), false);
        return 1;
    }

    private static int debugActionSummon(
            CommandSourceStack source, BlockPos position, String entityName) {
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        Identifier id = Identifier.tryParse(entityName);
        Optional<EntityType<?>> type = id == null
                ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (runtime == null || type.isEmpty()) {
            source.sendFailure(Component.literal(
                    "Lumi debug action requires a ready dimension and entity type"));
            return 0;
        }
        Entity entity = type.orElseThrow().create(source.getLevel(), EntitySpawnReason.COMMAND);
        if (entity == null) {
            source.sendFailure(Component.literal("Lumi debug entity could not be created"));
            return 0;
        }
        entity.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        boolean added;
        try (var ignored = DirectLiveActionContext.open(
                runtime.liveActions(), author(source).id())) {
            added = source.getLevel().addFreshEntity(entity);
        }
        if (!added) {
            source.sendFailure(Component.literal("Lumi debug entity could not be added"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Lumi debug entity action captured: " + entity.getUUID()), false);
        return 1;
    }

    private static CommitAuthor author(CommandSourceStack source) {
        var player = source.getPlayer();
        return player == null
                ? new CommitAuthor(new UUID(0, 0), source.getTextName())
                : new CommitAuthor(player.getUUID(), source.getTextName());
    }
}
