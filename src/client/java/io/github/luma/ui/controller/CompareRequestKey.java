package io.github.luma.ui.controller;

public record CompareRequestKey(
        String projectName,
        String leftVersionId,
        String rightVersionId
) {

    public CompareRequestKey {
        projectName = projectName == null ? "" : projectName;
        leftVersionId = leftVersionId == null ? "" : leftVersionId;
        rightVersionId = rightVersionId == null ? "" : rightVersionId;
    }

    public boolean valid() {
        return !this.projectName.isBlank()
                && !this.leftVersionId.isBlank()
                && !this.rightVersionId.isBlank()
                && !(CompareScreenController.CURRENT_WORLD_REFERENCE.equals(this.leftVersionId)
                && CompareScreenController.CURRENT_WORLD_REFERENCE.equals(this.rightVersionId));
    }
}
