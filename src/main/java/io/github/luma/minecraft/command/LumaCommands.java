package io.github.luma.minecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.luma.domain.model.ProjectArchiveExportResult;
import io.github.luma.domain.model.ProjectArchiveImportResult;
import io.github.luma.domain.service.ProjectArchiveService;
import io.github.luma.domain.service.ProjectCleanupService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.VariantService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.minecraft.animal.AnimalMoveManager;
import io.github.luma.minecraft.animal.AnimalMovePlan;
import io.github.luma.minecraft.animal.AnimalSelector;
import io.github.luma.minecraft.access.LumaAccessControl;
import java.io.IOException;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class LumaCommands {

    private final ProjectService projectService = new ProjectService();
    private final ProjectArchiveService projectArchiveService = new ProjectArchiveService();
    private final ProjectCleanupService projectCleanupService = new ProjectCleanupService();
    private final VersionService versionService = new VersionService();
    private final RestoreService restoreService = new RestoreService();
    private final VariantService variantService = new VariantService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final LumaAccessControl accessControl = LumaAccessControl.getInstance();
    private final AnimalMoveManager animalMoveManager = AnimalMoveManager.getInstance();
    private final AnimalMoveCommandParser animalMoveCommandParser = new AnimalMoveCommandParser();

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("lumi")
                .requires(this.accessControl::canUse);

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

        var archive = Commands.literal("archive");
        archive.then(Commands.literal("export")
                .then(Commands.argument("project", StringArgumentType.string())
                        .executes(context -> this.execute(context.getSource(), source -> this.exportProjectArchive(
                                source,
                                StringArgumentType.getString(context, "project"),
                                false
                        )))
                        .then(Commands.argument("includePreviews", BoolArgumentType.bool())
                                .executes(context -> this.execute(context.getSource(), source -> this.exportProjectArchive(
                                        source,
                                        StringArgumentType.getString(context, "project"),
                                        BoolArgumentType.getBool(context, "includePreviews")
                                ))))));
        archive.then(Commands.literal("import")
                .then(Commands.argument("archivePath", StringArgumentType.greedyString())
                        .executes(context -> this.execute(context.getSource(), source -> this.importProjectArchive(
                                source,
                                StringArgumentType.getString(context, "archivePath")
                        )))));
        root.then(archive);

        root.then(Commands.literal("cleanup")
                .then(Commands.literal("inspect")
                        .then(Commands.argument("project", StringArgumentType.string())
                                .executes(context -> this.execute(context.getSource(), source -> this.inspectCleanup(
                                        source,
                                        StringArgumentType.getString(context, "project")
                                )))))
                .then(Commands.literal("apply")
                        .then(Commands.argument("project", StringArgumentType.string())
                                .executes(context -> this.execute(context.getSource(), source -> this.applyCleanup(
                                        source,
                                        StringArgumentType.getString(context, "project")
                                ))))));

        dispatcher.register(root);
        this.registerAnimalMoveCommands(dispatcher);
    }

    private void registerAnimalMoveCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("animove")
                .requires(this.accessControl::canUse)
                .then(Commands.argument("radii", DoubleArgumentType.doubleArg(0.0D))
                        .then(Commands.argument("target", Vec3Argument.vec3())
                                .executes(context -> this.execute(context.getSource(), source -> this.startAnimalMove(
                                        source,
                                        DoubleArgumentType.getDouble(context, "radii"),
                                        Vec3Argument.getVec3(context, "target"),
                                        ""
                                )))
                                .then(Commands.argument("options", StringArgumentType.greedyString())
                                        .executes(context -> this.execute(context.getSource(), source -> this.startAnimalMove(
                                                source,
                                                DoubleArgumentType.getDouble(context, "radii"),
                                                Vec3Argument.getVec3(context, "target"),
                                                StringArgumentType.getString(context, "options")
                                        )))))));

        dispatcher.register(Commands.literal("animovestop")
                .requires(this.accessControl::canUse)
                .executes(context -> this.execute(context.getSource(), source -> this.stopAnimalMove(source, "")))
                .then(Commands.argument("animal_selector", StringArgumentType.greedyString())
                        .executes(context -> this.execute(context.getSource(), source -> this.stopAnimalMove(
                                source,
                                StringArgumentType.getString(context, "animal_selector")
                        )))));
    }

    private int listProjects(CommandSourceStack source) throws IOException {
        var projects = this.projectService.listProjects(source.getServer());
        if (projects.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Lumi: no projects yet"), false);
            return 1;
        }

        String names = projects.stream()
                .map(project -> project.name() + " [" + project.activeVariantId() + "]")
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Lumi projects: " + names), false);
        return projects.size();
    }

    private int createProject(CommandSourceStack source, String name, BlockPos from, BlockPos to) throws IOException {
        var project = this.projectService.createProject(source.getLevel(), name, from, to, source.getTextName());
        source.sendSuccess(() -> Component.literal("Created project " + project.name()), true);
        return 1;
    }

    private int saveVersion(CommandSourceStack source, String projectName, String message) throws IOException {
        var operation = this.versionService.startSaveVersion(source.getLevel(), projectName, message, source.getTextName());
        source.sendSuccess(() -> Component.literal(
                "Started save operation " + operation.id() + " for " + projectName
        ), true);
        return 1;
    }

    private int restoreVersion(CommandSourceStack source, String projectName, String versionId) throws IOException {
        var operation = this.restoreService.restore(source.getLevel(), projectName, versionId);
        source.sendSuccess(() -> Component.literal(
                "Started restore operation " + operation.id() + " for " + projectName
        ), true);
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
        var operation = this.recoveryService.restoreDraft(source.getLevel(), projectName);
        source.sendSuccess(() -> Component.literal(
                "Started recovery restore operation " + operation.id() + " for " + projectName
        ), true);
        return 1;
    }

    private int discardDraft(CommandSourceStack source, String projectName) throws IOException {
        this.recoveryService.discardDraft(source.getServer(), projectName);
        source.sendSuccess(() -> Component.literal("Discarded recovery draft for " + projectName), true);
        return 1;
    }

    private int exportProjectArchive(CommandSourceStack source, String projectName, boolean includePreviews) throws IOException {
        ProjectArchiveExportResult result = this.projectArchiveService.exportProject(source.getServer(), projectName, includePreviews);
        source.sendSuccess(() -> Component.literal(
                "Exported " + projectName + " to " + result.archiveFile() + " (" + result.manifest().entries().size() + " files)"
        ), true);
        return result.manifest().entries().size();
    }

    private int importProjectArchive(CommandSourceStack source, String archivePath) throws IOException {
        ProjectArchiveImportResult result = this.projectArchiveService.importProject(source.getServer(), archivePath);
        source.sendSuccess(() -> Component.literal(
                "Imported " + result.manifest().projectName() + " from " + result.archiveFile()
                        + " (" + result.manifest().entries().size() + " files)"
        ), true);
        return result.manifest().entries().size();
    }

    private int inspectCleanup(CommandSourceStack source, String projectName) throws IOException {
        var report = this.projectCleanupService.inspect(source.getServer(), projectName);
        source.sendSuccess(() -> Component.literal(
                "Cleanup dry run for " + projectName + ": " + report.candidates().size()
                        + " files, " + report.reclaimedBytes() + " bytes" + this.warningSuffix(report.warnings())
        ), false);
        return report.candidates().size();
    }

    private int applyCleanup(CommandSourceStack source, String projectName) throws IOException {
        var report = this.projectCleanupService.apply(source.getServer(), projectName);
        source.sendSuccess(() -> Component.literal(
                "Cleanup removed " + report.candidates().size() + " files from " + projectName
                        + " and reclaimed " + report.reclaimedBytes() + " bytes" + this.warningSuffix(report.warnings())
        ), true);
        return report.candidates().size();
    }

    private int startAnimalMove(CommandSourceStack source, double radius, Vec3 destination, String rawOptions) throws Exception {
        ServerLevel level = source.getLevel();
        AnimalMoveCommandOptions options = this.animalMoveCommandParser.parse(source, rawOptions);
        AnimalSelector selector = options.selector();
        var animals = selector.selectWithin(source, level, source.getPosition(), radius);
        int started = this.animalMoveManager.start(
                level,
                animals,
                new AnimalMovePlan(destination, options.returnPosition(), options.loop())
        );
        source.sendSuccess(() -> Component.literal(
                "Animove started for " + started + " animals"
                        + (options.loop() ? " in loop" : "")
                        + " using " + selector
        ), true);
        return started;
    }

    private int stopAnimalMove(CommandSourceStack source, String rawSelector) throws Exception {
        ServerLevel level = source.getLevel();
        int stopped;
        if (rawSelector == null || rawSelector.isBlank()) {
            stopped = this.animalMoveManager.stopAll(level);
        } else {
            AnimalSelector selector = this.animalMoveCommandParser.parseSelector(rawSelector);
            stopped = this.animalMoveManager.stop(level, selector.selectedAnimalIds(source));
        }
        source.sendSuccess(() -> Component.literal("Animove stopped " + stopped + " animals"), true);
        return stopped;
    }

    private int execute(CommandSourceStack source, IoAction action) {
        try {
            return action.run(source);
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Lumi error: " + exception.getMessage()));
            return 0;
        }
    }

    @FunctionalInterface
    private interface IoAction {
        int run(CommandSourceStack source) throws Exception;
    }

    private String warningSuffix(java.util.List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return "";
        }
        return " (" + String.join("; ", warnings) + ")";
    }
}
