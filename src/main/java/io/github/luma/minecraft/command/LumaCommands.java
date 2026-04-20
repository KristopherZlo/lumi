package io.github.luma.minecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.VariantService;
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
    private final VariantService variantService = new VariantService();
    private final RecoveryService recoveryService = new RecoveryService();

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

        root.then(Commands.literal("variant")
                .then(Commands.literal("list")
                        .then(Commands.argument("project", StringArgumentType.string())
                                .executes(context -> this.execute(context.getSource(), source -> this.listVariants(
                                        source,
                                        StringArgumentType.getString(context, "project")
                                )))))
                .then(Commands.literal("create")
                        .then(Commands.argument("project", StringArgumentType.string())
                                .then(Commands.argument("variant", StringArgumentType.string())
                                        .executes(context -> this.execute(context.getSource(), source -> this.createVariant(
                                                source,
                                                StringArgumentType.getString(context, "project"),
                                                StringArgumentType.getString(context, "variant"),
                                                ""
                                        )))
                                        .then(Commands.argument("fromVersion", StringArgumentType.string())
                                                .executes(context -> this.execute(context.getSource(), source -> this.createVariant(
                                                        source,
                                                        StringArgumentType.getString(context, "project"),
                                                        StringArgumentType.getString(context, "variant"),
                                                        StringArgumentType.getString(context, "fromVersion")
                                                )))))))
                .then(Commands.literal("switch")
                        .then(Commands.argument("project", StringArgumentType.string())
                                .then(Commands.argument("variant", StringArgumentType.string())
                                        .executes(context -> this.execute(context.getSource(), source -> this.switchVariant(
                                                source,
                                                StringArgumentType.getString(context, "project"),
                                                StringArgumentType.getString(context, "variant")
                                        )))))));

        root.then(Commands.literal("recovery")
                .then(Commands.literal("status")
                        .then(Commands.argument("project", StringArgumentType.string())
                                .executes(context -> this.execute(context.getSource(), source -> this.recoveryStatus(
                                        source,
                                        StringArgumentType.getString(context, "project")
                                )))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("project", StringArgumentType.string())
                                .executes(context -> this.execute(context.getSource(), source -> this.restoreDraft(
                                        source,
                                        StringArgumentType.getString(context, "project")
                                )))))
                .then(Commands.literal("discard")
                        .then(Commands.argument("project", StringArgumentType.string())
                                .executes(context -> this.execute(context.getSource(), source -> this.discardDraft(
                                        source,
                                        StringArgumentType.getString(context, "project")
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
                .map(project -> project.name() + " [" + project.activeVariantId() + "]")
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

    private int listVariants(CommandSourceStack source, String projectName) throws IOException {
        var variants = this.variantService.listVariants(source.getServer(), projectName);
        String body = variants.stream()
                .map(variant -> variant.id() + " -> " + variant.headVersionId())
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Variants: " + body), false);
        return variants.size();
    }

    private int createVariant(CommandSourceStack source, String projectName, String variantName, String fromVersionId) throws IOException {
        var variant = this.variantService.createVariant(source.getServer(), projectName, variantName, fromVersionId);
        source.sendSuccess(() -> Component.literal("Created variant " + variant.id()), true);
        return 1;
    }

    private int switchVariant(CommandSourceStack source, String projectName, String variantId) throws IOException {
        var variant = this.variantService.switchVariant(source.getLevel(), projectName, variantId);
        source.sendSuccess(() -> Component.literal("Switched active variant to " + variant.id()), true);
        return 1;
    }

    private int recoveryStatus(CommandSourceStack source, String projectName) throws IOException {
        var draft = this.recoveryService.loadDraft(source.getServer(), projectName);
        if (draft.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No recovery draft for " + projectName), false);
            return 1;
        }

        var loadedDraft = draft.get();
        source.sendSuccess(() -> Component.literal(
                "Recovery draft: " + loadedDraft.changes().size() + " tracked changes on variant " + loadedDraft.variantId()
        ), false);
        return loadedDraft.changes().size();
    }

    private int restoreDraft(CommandSourceStack source, String projectName) throws IOException {
        var draft = this.recoveryService.restoreDraft(source.getLevel(), projectName);
        source.sendSuccess(() -> Component.literal("Re-applied recovery draft with " + draft.changes().size() + " changes"), true);
        return 1;
    }

    private int discardDraft(CommandSourceStack source, String projectName) throws IOException {
        this.recoveryService.discardDraft(source.getServer(), projectName);
        source.sendSuccess(() -> Component.literal("Discarded recovery draft for " + projectName), true);
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
