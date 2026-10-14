package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.WorkingIndexSnapshot;

public record SaveResult(
        CommitId commitId, BranchRef branchRef, WorkingIndexSnapshot capturedGenerations) {
}
