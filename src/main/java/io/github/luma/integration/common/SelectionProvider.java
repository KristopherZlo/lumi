package io.github.luma.integration.common;

import java.util.Optional;

public interface SelectionProvider extends ExternalToolAdapter {

    Optional<ExternalSelectionSnapshot> currentSelection(String actor, String dimensionId);
}
