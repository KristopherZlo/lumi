package io.github.luma.ui.state;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;

public record BranchCreationDialogState(
        ProjectVersion baseVersion,
        ProjectVariant baseVariant,
        String branchName,
        boolean operationActive
) {

    public BranchCreationDialogState {
        branchName = branchName == null ? "" : branchName;
    }

    public static BranchCreationDialogState hidden(String branchName) {
        return new BranchCreationDialogState(null, null, branchName, false);
    }

    public boolean visible() {
        return this.baseVersion != null;
    }

    public boolean canCreate() {
        return this.visible() && !this.operationActive && !this.branchName.trim().isBlank();
    }

    public String baseVersionName() {
        if (this.baseVersion == null) {
            return "";
        }
        String message = this.baseVersion.message();
        return message == null || message.isBlank() ? this.baseVersion.id() : message;
    }

    public String baseVariantName() {
        if (this.baseVariant == null) {
            return "";
        }
        String name = this.baseVariant.name();
        return name == null || name.isBlank() ? this.baseVariant.id() : name;
    }
}
