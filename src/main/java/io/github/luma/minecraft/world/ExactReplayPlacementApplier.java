package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;
import net.minecraft.server.level.ServerLevel;

@FunctionalInterface
interface ExactReplayPlacementApplier {

    boolean apply(
            ServerLevel level,
            PreparedBlockPlacement placement,
            OperationHandle handle,
            String phase
    ) throws Exception;
}
