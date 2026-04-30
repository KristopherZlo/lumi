package io.github.luma.minecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.testing.SingleplayerTestingService;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class LumaCommands {

    private final ProjectService projectService = new ProjectService();
    private final LumaAccessControl accessControl = LumaAccessControl.getInstance();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final SingleplayerTestingService singleplayerTestingService = SingleplayerTestingService.getInstance();

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("lumi")
                .requires(this.accessControl::canUse)
                .executes(context -> this.execute(context.getSource(), this::help));

        root.then(Commands.literal("help")
                .executes(context -> this.execute(context.getSource(), this::help)));

        root.then(Commands.literal("status")
                .executes(context -> this.execute(context.getSource(), this::status)));

        root.then(Commands.literal("testing")
                .then(Commands.literal("singleplayer")
                        .executes(context -> this.execute(context.getSource(), this.singleplayerTestingService::start))));

        dispatcher.register(root);
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Lumi commands are diagnostics and local testing tools."), false);
        source.sendSuccess(() -> Component.literal("/lumi-onboarding - replay the short Lumi onboarding tour"), false);
        source.sendSuccess(() -> Component.literal("/lumi status - show project and operation status"), false);
        source.sendSuccess(() -> Component.literal("/lumi testing singleplayer - run the integrated-world Lumi regression suite"), false);
        source.sendSuccess(() -> Component.literal("Use the Lumi UI for project creation, save, restore, variants, recovery, share, merge, import/export, and cleanup."), false);
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
}
