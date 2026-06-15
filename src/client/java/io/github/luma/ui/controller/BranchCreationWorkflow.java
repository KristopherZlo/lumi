package io.github.luma.ui.controller;

import io.github.luma.domain.model.ProjectVariant;
import java.util.Objects;

final class BranchCreationWorkflow {

    private final BranchCreator branchCreator;
    private final BranchSwitcher branchSwitcher;

    BranchCreationWorkflow(BranchCreator branchCreator, BranchSwitcher branchSwitcher) {
        this.branchCreator = Objects.requireNonNull(branchCreator, "branchCreator");
        this.branchSwitcher = Objects.requireNonNull(branchSwitcher, "branchSwitcher");
    }

    ProjectVariant createAndSwitch(String projectName, String branchName, String baseVersionId) throws Exception {
        ProjectVariant created = this.branchCreator.create(projectName, branchName, baseVersionId);
        if (created == null || created.id() == null || created.id().isBlank()) {
            throw new IllegalStateException("Created branch id is missing");
        }
        this.branchSwitcher.switchTo(projectName, created.id());
        return created;
    }

    @FunctionalInterface
    interface BranchCreator {

        ProjectVariant create(String projectName, String branchName, String baseVersionId) throws Exception;
    }

    @FunctionalInterface
    interface BranchSwitcher {

        void switchTo(String projectName, String branchId) throws Exception;
    }
}
