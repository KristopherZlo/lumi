package io.github.luma.minecraft.access;

import io.github.luma.domain.model.ProjectSettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.GameType;

/**
 * Central permission gate for world-mutating Lumi features.
 */
public final class LumaAccessControl {

    public static final PermissionLevel REQUIRED_PERMISSION_LEVEL = PermissionLevel.GAMEMASTERS;
    private static final LumaAccessControl INSTANCE = new LumaAccessControl();

    private LumaAccessControl() {
    }

    public static LumaAccessControl getInstance() {
        return INSTANCE;
    }

    public boolean canUse(CommandSourceStack source) {
        return source != null && this.hasRequiredLevel(source.permissions());
    }

    public boolean canUse(ServerPlayer player) {
        return player != null && this.hasRequiredLevel(player.permissions());
    }

    public boolean canUse(ServerPlayer player, ProjectSettings settings) {
        return this.canUse(settings, this.survivalMode(player), this.canUse(player));
    }

    public boolean canUse(ProjectSettings settings, boolean survivalMode, boolean permissionAllowed) {
        if (!permissionAllowed) {
            return false;
        }
        return !survivalMode || ProjectSettings.sanitize(settings).survivalModeAllowed();
    }

    public boolean survivalMode(ServerPlayer player) {
        return player != null && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
    }

    public boolean survivalModeDisabled(ServerPlayer player, ProjectSettings settings) {
        return this.survivalMode(player) && !ProjectSettings.sanitize(settings).survivalModeAllowed();
    }

    private boolean hasRequiredLevel(PermissionSet permissions) {
        return permissions instanceof LevelBasedPermissionSet levelBased
                && levelBased.level().isEqualOrHigherThan(REQUIRED_PERMISSION_LEVEL);
    }
}
