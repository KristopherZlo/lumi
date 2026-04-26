package io.github.luma.integration.common;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.WorldMutationSource;
import java.util.LinkedHashMap;
import java.util.Map;

public record ExternalOperationMetadata(
        String toolId,
        WorldMutationSource source,
        String actor,
        String actionId,
        String operationType,
        String operationLabel,
        Bounds3i bounds,
        boolean usedClipboard,
        boolean usedSelection,
        Map<String, String> metadata
) {

    public ExternalOperationMetadata {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public ExternalSourceInfo toSourceInfo() {
        Map<String, String> sourceMetadata = new LinkedHashMap<>(this.metadata);
        if (this.actionId != null && !this.actionId.isBlank()) {
            sourceMetadata.put("actionId", this.actionId);
        }
        if (this.source != null) {
            sourceMetadata.put("mutationSource", this.source.name());
        }
        return new ExternalSourceInfo(
                this.toolId,
                this.operationType,
                this.operationLabel,
                this.actor,
                this.bounds,
                this.usedClipboard,
                this.usedSelection,
                sourceMetadata
        );
    }
}
