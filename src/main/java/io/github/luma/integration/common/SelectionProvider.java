package io.github.luma.integration.common;

import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

public interface SelectionProvider extends ExternalToolAdapter {

    default Optional<ExternalSelectionSnapshot> currentSelection(ServerPlayer player) {
        return Optional.empty();
    }

    Optional<ExternalSelectionSnapshot> currentSelection(String actor, String dimensionId);
}
