package io.github.luma.minecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.VersionService;
import java.io.IOException;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class LumaCommands {

    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final RestoreService restoreService = new RestoreService();

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("luma");

        root.then(Commands.literal("list")
                .executes(context -> this.execute(context.getSource(), this::listProjects)));

        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(context -> this.execute(context.getSource(), source -> this.createProject(
                                                source,
                                                StringArgumentType.getString(context, "name"),
                                                BlockPosArgument.getBlockPos(context, "from"),
                                                BlockPosArgument.getBlockPos(context, "to")
                                        )))))));

        root.then(Commands.literal("save")
                .then(Commands.argument("project", StringArgumentType.string())
                        .executes(context -> this.execute(context.getSource(), source -> this.saveVersion(
                                source,
                                StringArgumentType.getString(context, "project"),
                                ""
                        )))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> this.execute(context.getSource(), source -> this.saveVersion(
                                        source,
                                        StringArgumentType.getString(context, "project"),
                                        StringArgumentType.getString(context, "message")
                                ))))));

        root.then(Commands.literal("restore")
                .then(Commands.argument("project", StringArgumentType.string())
                        .executes(context -> this.execute(context.getSource(), source -> this.restoreVersion(
                                source,
                                StringArgumentType.getString(context, "project"),
                                ""
                        )))
                        .then(Commands.argument("version", StringArgumentType.string())
                                .executes(context -> this.execute(context.getSource(), source -> this.restoreVersion(
                                        source,
                                        StringArgumentType.getString(context, "project"),
                                        StringArgumentType.getString(context, "version")
                                ))))));

        dispatcher.register(root);
    }

    private int listProjects(CommandSourceStack source) throws IOException {
        var projects = this.projectService.listProjects(source.getServer());
        if (projects.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Luma: no projects yet"), false);
            return 1;
        }

        String names = projects.stream()
                .map(project -> project.name() + " [" + project.bounds().volume() + "]")
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Luma projects: " + names), false);
        return projects.size();
    }

    private int createProject(CommandSourceStack source, String name, BlockPos from, BlockPos to) throws IOException {
        var project = this.projectService.createProject(source.getLevel(), name, from, to, source.getTextName());
        source.sendSuccess(() -> Component.literal("Created project " + project.name()), true);
        return 1;
    }

    private int saveVersion(CommandSourceStack source, String projectName, String message) throws IOException {
        var version = this.versionService.saveVersion(source.getLevel(), projectName, message, source.getTextName());
        source.sendSuccess(() -> Component.literal("Saved version " + version.id() + " for " + projectName), true);
        return 1;
    }

    private int restoreVersion(CommandSourceStack source, String projectName, String versionId) throws IOException {
        var version = this.restoreService.restore(source.getLevel(), projectName, versionId);
        source.sendSuccess(() -> Component.literal("Restored version " + version.id() + " for " + projectName), true);
        return 1;
    }

    private int execute(CommandSourceStack source, IoAction action) {
        try {
            return action.run(source);
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Luma error: " + exception.getMessage()));
            return 0;
        }
    }

    @FunctionalInterface
    private interface IoAction {
        int run(CommandSourceStack source) throws Exception;
    }
}
