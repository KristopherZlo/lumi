package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import net.minecraft.server.MinecraftServer;

final class CaptureAccessGuard {

    private final CaptureEligibilityService eligibility;

    CaptureAccessGuard(CaptureEligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    boolean canUseMutationSource(MinecraftServer server, WorldMutationSource source) {
        return this.eligibility.canUseMutationSource(
                server != null && server.isDedicatedServer(),
                WorldMutationContext.currentAccessAllowed(),
                source
        );
    }

    boolean canCreateProjectInCurrentMode() {
        return LumaAccessControl.getInstance().canUse(
                ProjectSettings.defaults(),
                WorldMutationContext.currentSurvivalMode(),
                WorldMutationContext.currentAccessAllowed()
        );
    }

    boolean canUseProjectInCurrentMode(TrackedProject trackedProject) {
        return trackedProject != null && LumaAccessControl.getInstance().canUse(
                trackedProject.project().settings(),
                WorldMutationContext.currentSurvivalMode(),
                WorldMutationContext.currentAccessAllowed()
        );
    }
}
