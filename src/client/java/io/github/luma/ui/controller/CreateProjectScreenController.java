package io.github.luma.ui.controller;

import io.github.luma.domain.service.ProjectService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class CreateProjectScreenController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();

    public BlockPos suggestedCenter() {
        return this.client.player == null ? BlockPos.ZERO : this.client.player.blockPosition();
    }

    public String createProject(String name, BlockPos from, BlockPos to) {
        if (name == null || name.isBlank()) {
            return "luma.status.project_invalid_name";
        }

        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var dimension = this.client.level == null ? Level.OVERWORLD : this.client.level.dimension();
            var level = server.getLevel(dimension);
            if (level == null) {
                level = server.overworld();
            }

            this.projectService.createProject(level, name, from, to, this.client.getUser().getName());
            return "luma.status.project_created";
        } catch (Exception exception) {
            return "luma.status.operation_failed";
        }
    }
}
