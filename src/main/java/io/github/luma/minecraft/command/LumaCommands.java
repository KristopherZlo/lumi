package io.github.luma.minecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class LumaCommands {

    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final LumaAccessControl accessControl = LumaAccessControl.getInstance();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("lumi")
                .requires(this.accessControl::canUse)
                .executes(context -> LumaCommandExecutor.execute(context.getSource(), this::help));

        root.then(Commands.literal("help")
                .executes(context -> LumaCommandExecutor.execute(context.getSource(), this::help)));

        root.then(Commands.literal("status")
                .executes(context -> LumaCommandExecutor.execute(context.getSource(), this::status)));

        root.then(Commands.literal("save")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> LumaCommandExecutor.execute(context.getSource(), source ->
                                this.save(source, StringArgumentType.getString(context, "message"))))));

        dispatcher.register(root);
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Lumi commands require operator-level permission."), false);
        source.sendSuccess(() -> Component.literal("/lumi-onboarding - replay the short Lumi onboarding tour"), false);
        source.sendSuccess(() -> Component.literal("/lumi status - show project and operation status"), false);
        source.sendSuccess(() -> Component.literal("/lumi save <message> - save current tracked changes"), false);
        source.sendSuccess(() -> Component.literal("Use the Lumi UI for project creation, save, restore, variants, recovery, share, merge, import/export, and cleanup."), false);
        return 1;
    }

    private int save(CommandSourceStack source, String message) throws Exception {
        String normalizedMessage = message == null ? "" : message.trim();
        if (normalizedMessage.isBlank()) {
            source.sendFailure(Component.literal("Lumi save needs a message."));
            return 0;
        }
        var player = source.getPlayerOrException();
        String author = player.getName().getString();
        Optional<BuildProject> existing = this.projectService.findWorldProject(source.getLevel());
        ProjectSettings settings = existing.map(BuildProject::settings).orElse(ProjectSettings.defaults());
        if (!this.accessControl.canUse(player, settings)) {
            source.sendFailure(Component.translatable(this.accessControl.survivalModeDisabled(player, settings)
                    ? "luma.status.survival_disabled"
                    : "luma.status.admin_required"));
            return 0;
        }
        BuildProject project = existing.isPresent()
                ? existing.get()
                : this.projectService.ensureWorldProject(source.getLevel(), author);
        var handle = this.versionService.startSaveVersion(source.getLevel(), project.name(), normalizedMessage, author);
        source.sendSuccess(() -> Component.literal("Lumi save started: " + handle.id()), false);
        return 1;
    }

    private int status(CommandSourceStack source) throws IOException {
        var projects = this.projectService.listProjects(source.getServer());
        source.sendSuccess(() -> Component.literal(this.projectSummary(projects)), false);
        source.sendSuccess(() -> Component.literal(this.operationSummary(this.worldOperationManager.snapshot(source.getServer()))), false);
        return Math.max(1, projects.size());
    }

    private String projectSummary(java.util.List<io.github.luma.domain.model.BuildProject> projects) {
        if (projects.isEmpty()) {
            return "Lumi projects: none";
        }
        String names = projects.stream()
                .map(project -> project.name() + " [" + project.activeVariantId() + "]")
                .collect(Collectors.joining(", "));
        return "Lumi projects: " + projects.size() + " (" + names + ")";
    }

    private String operationSummary(Optional<OperationSnapshot> snapshot) {
        if (snapshot.isEmpty()) {
            return "Lumi operation: none";
        }
        OperationSnapshot operation = snapshot.get();
        String state = operation.terminal() ? "last" : "active";
        String id = operation.handle() == null ? "unknown" : operation.handle().id();
        String label = operation.handle() == null ? "operation" : operation.handle().label();
        String progress = operation.progress() == null
                ? ""
                : " " + operation.progress().completedUnits() + "/" + operation.progress().totalUnits()
                + " " + operation.progress().unitLabel();
        String detail = operation.detail() == null || operation.detail().isBlank() ? "" : " - " + operation.detail();
        return "Lumi operation: " + state + " " + label + " " + id + " " + operation.stage() + progress + detail;
    }

}
