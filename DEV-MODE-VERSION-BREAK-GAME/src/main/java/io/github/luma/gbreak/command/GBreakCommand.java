package io.github.luma.gbreak.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.luma.gbreak.bug.GameBreakingBug;
import io.github.luma.gbreak.server.FakeRestoreService;
import io.github.luma.gbreak.state.BugStateController;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

public final class GBreakCommand {

    private GBreakCommand() {
    }

    public static void register(FakeRestoreService fakeRestoreService) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("gbreak")
                        .then(CommandManager.literal("fakerestore")
                                .executes(context -> fakeRestore(
                                        context.getSource(),
                                        fakeRestoreService,
                                        "latest"
                                ))
                                .then(CommandManager.argument("target", StringArgumentType.string())
                                        .executes(context -> fakeRestore(
                                                context.getSource(),
                                                fakeRestoreService,
                                                StringArgumentType.getString(context, "target")
                                        ))))
                        .then(CommandManager.literal("off").executes(context -> disable(context.getSource())))
                        .then(CommandManager.literal("disable").executes(context -> disable(context.getSource())))
                        .then(CommandManager.literal("none").executes(context -> disable(context.getSource())))
                        .then(CommandManager.argument("bug", StringArgumentType.string())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        GameBreakingBug.commandSuggestions(),
                                        builder
                                ))
                                .executes(context -> activate(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "bug")
                                )))
                        .executes(context -> list(context.getSource()))
        ));
    }

    private static int fakeRestore(
            net.minecraft.server.command.ServerCommandSource source,
            FakeRestoreService fakeRestoreService,
            String target
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!fakeRestoreService.start(source.getPlayerOrThrow(), target)) {
            source.sendError(Text.translatable("gbreakdev.restore.command_busy"));
            return 0;
        }
        return 1;
    }

    private static int disable(net.minecraft.server.command.ServerCommandSource source) {
        BugStateController.getInstance().activate(GameBreakingBug.NONE);
        source.sendFeedback(() -> Text.literal("GBreak disabled"), false);
        return 1;
    }

    private static int activate(net.minecraft.server.command.ServerCommandSource source, String rawBug) {
        GameBreakingBug bug = GameBreakingBug.parse(rawBug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown bug token: " + rawBug));
        BugStateController.getInstance().activate(bug);
        if (bug == GameBreakingBug.NONE) {
            source.sendFeedback(() -> Text.literal("GBreak disabled"), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("GBreak active: " + bug.displayLabel() + " | " + bug.description()), false);
        return bug.id();
    }

    private static int list(net.minecraft.server.command.ServerCommandSource source) {
        StringBuilder builder = new StringBuilder("GBreak bugs: ");
        GameBreakingBug[] values = GameBreakingBug.values();
        for (int index = 0; index < values.length; index++) {
            GameBreakingBug bug = values[index];
            if (index > 0) {
                builder.append(" | ");
            }
            builder.append(bug.displayLabel());
        }
        source.sendFeedback(() -> Text.literal(builder.toString()), false);
        return 1;
    }
}
