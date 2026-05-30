package io.github.luma.minecraft.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.luma.minecraft.testing.SingleplayerTestingService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Opt-in runtime regression command tree.
 */
public final class LumaTestingCommands {

    private final SingleplayerTestingService singleplayerTestingService;

    public LumaTestingCommands(SingleplayerTestingService singleplayerTestingService) {
        this.singleplayerTestingService = singleplayerTestingService;
    }

    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("testing")
                .then(Commands.literal("singleplayer")
                        .executes(context -> LumaCommandExecutor.execute(
                                context.getSource(),
                                this.singleplayerTestingService::start
                        )))
                .then(Commands.literal("smoke")
                        .executes(context -> LumaCommandExecutor.execute(
                                context.getSource(),
                                this.singleplayerTestingService::startSmoke
                        )))
                .then(Commands.literal("player-flow")
                        .executes(context -> LumaCommandExecutor.execute(
                                context.getSource(),
                                this.singleplayerTestingService::startPlayerFlow
                        )))
                .then(Commands.literal("structures")
                        .executes(context -> LumaCommandExecutor.execute(
                                context.getSource(),
                                this.singleplayerTestingService::startStructureFixtures
                        )))
                .then(Commands.literal("crash-safety")
                        .executes(context -> LumaCommandExecutor.execute(
                                context.getSource(),
                                this.singleplayerTestingService::startCrashSafety
                        )))
                .then(Commands.literal("external-tools")
                        .executes(context -> LumaCommandExecutor.execute(
                                context.getSource(),
                                this.singleplayerTestingService::startExternalTools
                        ))));
    }
}
