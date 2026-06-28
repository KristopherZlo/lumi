package io.github.luma.ui.state;

import io.github.luma.domain.model.RestoreEntityTypeCount;
import io.github.luma.domain.model.RestoreEntityTypeSelection;
import io.github.luma.ui.screen.section.RestoreConfirmationDialogView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RestoreEntitySelectionState {

    private final Set<String> excludedEntityTypes = new LinkedHashSet<>();
    private boolean expanded;

    public boolean expanded() {
        return this.expanded;
    }

    public void toggleExpanded() {
        this.expanded = !this.expanded;
    }

    public void toggleEntityType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return;
        }
        if (!this.excludedEntityTypes.add(entityType)) {
            this.excludedEntityTypes.remove(entityType);
        }
    }

    public RestoreEntityTypeSelection selection() {
        return RestoreEntityTypeSelection.excludeTypes(this.excludedEntityTypes);
    }

    public List<RestoreConfirmationDialogView.EntityTypeOption> options(List<RestoreEntityTypeCount> counts) {
        return (counts == null ? List.<RestoreEntityTypeCount>of() : counts).stream()
                .map(count -> new RestoreConfirmationDialogView.EntityTypeOption(
                        count.entityType(),
                        count.count(),
                        !this.excludedEntityTypes.contains(count.entityType())
                ))
                .toList();
    }

    public void reset() {
        this.excludedEntityTypes.clear();
        this.expanded = false;
    }
}
