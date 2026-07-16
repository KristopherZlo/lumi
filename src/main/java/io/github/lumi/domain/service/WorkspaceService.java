package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.Workspace;
import io.github.lumi.domain.model.WorkspaceSettings;
import io.github.lumi.domain.model.WorkspaceSwitchPlan;
import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.storage.repository.ActiveWorkspaceRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorkspaceRepository;
import io.github.lumi.storage.repository.RefConflictException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates workspace history roots without copying any dimension payload. */
public final class WorkspaceService {
    private final WorkspaceRepository workspaces;
    private final ActiveWorkspaceRepository active;
    private final CommitRepository commits;
    private final BranchRefRepository refs;

    public WorkspaceService(
            WorkspaceRepository workspaces,
            ActiveWorkspaceRepository active,
            CommitRepository commits,
            BranchRefRepository refs) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.active = Objects.requireNonNull(active, "active");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public synchronized Workspace initializeDefault(UUID id) throws IOException {
        Objects.requireNonNull(id, "id");
        Workspace workspace = workspaces.read(id).orElse(null);
        if (workspace == null) {
            workspace = workspaces.create(new Workspace(
                    id, "Default workspace", Optional.empty(), WorkspaceSettings.defaults()));
        }
        var selected = active.read();
        if (selected.isEmpty()) {
            active.create(id);
        } else if (workspaces.read(selected.orElseThrow().id()).isEmpty()) {
            throw new IOException("Active workspace metadata is missing");
        }
        return workspace;
    }

    public synchronized Creation create(
            UUID id,
            String name,
            Optional<BlockBox> bounds,
            WorkspaceSettings settings,
            BranchRef from,
            CommitAuthor author,
            Instant timestamp) throws IOException {
        Objects.requireNonNull(id, "id");
        Workspace workspace = new Workspace(id, name, bounds, settings);
        if (workspaces.read(id).isPresent()) {
            throw new IOException("Workspace already exists: " + id);
        }
        Commit source = commits.read(Objects.requireNonNull(from, "from").commit());
        BranchRef initial = createInitialCommit(
                workspace, from, source, Objects.requireNonNull(author, "author"),
                Objects.requireNonNull(timestamp, "timestamp"));
        workspaces.create(workspace);
        return new Creation(workspace, initial);
    }

    public Workspace active() throws IOException {
        var selected = active.read().orElseThrow(
                () -> new IOException("Active workspace is missing"));
        return require(selected.id());
    }

    public List<Workspace> list() throws IOException {
        return workspaces.list();
    }

    public Workspace require(UUID id) throws IOException {
        return workspaces.read(Objects.requireNonNull(id, "id")).orElseThrow(
                () -> new IOException("Active workspace metadata is missing"));
    }

    public WorkspaceSwitchPlan prepareSwitch(UUID targetId, BranchSwitchPlan branch) throws IOException {
        Objects.requireNonNull(branch, "branch");
        Workspace target = require(targetId);
        var expected = active.read().orElseThrow(
                () -> new IOException("Active workspace is missing"));
        if (!branch.target().name().equals(mainBranch(target.id()))) {
            throw new IOException("Workspace switch does not target its main branch");
        }
        if (!commits.read(branch.source().commit()).workspaceId().equals(expected.id())) {
            throw new IOException("Active branch does not belong to active workspace");
        }
        if (!commits.read(branch.target().commit()).workspaceId().equals(target.id())) {
            throw new IOException("Target branch does not belong to target workspace");
        }
        return new WorkspaceSwitchPlan(expected, target.id(), branch);
    }

    public void completeSwitchIdempotent(WorkspaceSwitchPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        require(plan.targetWorkspace());
        var selected = active.read().orElseThrow(
                () -> new RefConflictException("Active workspace no longer exists"));
        var published = new io.github.lumi.domain.model.ActiveWorkspace(
                plan.targetWorkspace(), Math.addExact(plan.expectedActive().revision(), 1));
        if (selected.equals(published)) {
            return;
        }
        if (!selected.equals(plan.expectedActive())) {
            throw new RefConflictException("Active workspace changed during switch");
        }
        active.compareAndSet(plan.expectedActive(), plan.targetWorkspace());
    }

    public void validateSwitch(WorkspaceSwitchPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        require(plan.targetWorkspace());
        var selected = active.read().orElseThrow(
                () -> new RefConflictException("Active workspace no longer exists"));
        if (!selected.equals(plan.expectedActive())) {
            throw new RefConflictException("Active workspace changed during switch");
        }
    }

    public static BranchName mainBranch(UUID workspaceId) {
        return new BranchName("workspace/" + Objects.requireNonNull(workspaceId, "workspaceId")
                + "/main");
    }

    public static BranchName branchName(UUID workspaceId, BranchName visibleName) {
        return new BranchName("workspace/" + Objects.requireNonNull(workspaceId, "workspaceId")
                + "/" + Objects.requireNonNull(visibleName, "visibleName").value());
    }

    public static BranchName visibleBranchName(
            UUID workspaceId,
            UUID defaultWorkspaceId,
            BranchName visibleName) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId");
        Objects.requireNonNull(visibleName, "visibleName");
        return workspaceId.equals(defaultWorkspaceId) && visibleName.value().equals("main")
                ? visibleName : branchName(workspaceId, visibleName);
    }

    private BranchRef createInitialCommit(
            Workspace workspace,
            BranchRef from,
            Commit source,
            CommitAuthor author,
            Instant timestamp) throws IOException {
        var id = commits.write(new Commit(
                source.tree(), List.of(from.commit()), author, "Initial workspace",
                timestamp, workspace.id(), Optional.empty(), CommitKind.HIDDEN_SAFETY,
                new CommitStatistics(0, 0, 0, 0), source.playerSpawns()));
        return refs.create(mainBranch(workspace.id()), id);
    }

    public record Creation(Workspace workspace, BranchRef main) { }

}
