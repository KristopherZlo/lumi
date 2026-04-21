package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryDraftSummary;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Handles interrupted tracked-work recovery.
 *
 * <p>The recovery workflow can expose the current draft, restore it into the
 * world, save it as a normal version, or discard it while keeping the journal
 * consistent.
 */
public final class RecoveryService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    public Optional<RecoveryDraft> loadDraft(MinecraftServer server, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        return HistoryCaptureManager.getInstance().snapshotDraft(server, project.id().toString());
    }

    public List<RecoveryJournalEntry> loadJournal(MinecraftServer server, String projectName) throws IOException {
        return this.recoveryRepository.loadJournal(this.projectService.resolveLayout(server, projectName));
    }

    public Optional<RecoveryDraftSummary> summarizeDraft(MinecraftServer server, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        Optional<RecoveryDraft> draft = HistoryCaptureManager.getInstance().snapshotDraft(server, project.id().toString());
        if (draft.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(RecoveryDraftSummary.from(draft.get(), this.variantRepository.loadAll(layout)));
    }

    public void saveSessionDraft(ProjectLayout layout, TrackedChangeBuffer session) throws IOException {
        this.recoveryRepository.saveDraft(layout, session.toDraft());
    }

    /**
     * Starts an operation that reapplies the current recovery draft to the
     * world.
     */
    public OperationHandle restoreDraft(ServerLevel level, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout);
        Optional<TrackedChangeBuffer> frozenSession = HistoryCaptureManager.getInstance()
                .freezeSession(level.getServer(), project.id().toString());
        Optional<RecoveryDraft> frozenDraft = frozenSession.map(TrackedChangeBuffer::toDraft);
        RecoveryDraft draft = frozenDraft
                .or(() -> persistedDraft)
                .orElseThrow(() -> new IllegalArgumentException("No recovery draft available for " + projectName));
        LumaMod.LOGGER.info(
                "Starting recovery draft restore for project {} with {} changes",
                project.name(),
                draft.changes().size()
        );
        LumaDebugLog.log(
                project,
                "recovery",
                "Starting recovery restore for project {} with {} changes from {}",
                project.name(),
                draft.changes().size(),
                frozenDraft.isPresent() ? "frozen live buffer" : "persisted draft"
        );

        Instant now = Instant.now();
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "draft-restore-started",
                "Started applying recovery draft",
                draft.baseVersionId(),
                draft.variantId()
        ));

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                "restore-draft",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> {
                    progressSink.update(OperationStage.PREPARING, 0, draft.changes().size(), "Decoding recovery draft");
                    List<PreparedChunkBatch> batches = this.decodeDraft(level, draft.changes(), progressSink);
                    return new WorldOperationManager.PreparedApplyOperation(
                            batches,
                            () -> {
                                this.recoveryRepository.deleteDraft(layout);
                                this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                                        Instant.now(),
                                        "draft-restored",
                                        "Applied recovery draft to the world",
                                        draft.baseVersionId(),
                                        draft.variantId()
                                ));
                                HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
                                LumaMod.LOGGER.info(
                                        "Completed recovery draft restore for project {} with {} changes",
                                        project.name(),
                                        draft.changes().size()
                                );
                            }
                    );
                }
        );
    }

    /**
     * Deletes the current recovery draft for a project and appends a journal
     * entry describing the discard action.
     */
    public void discardDraft(MinecraftServer server, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        HistoryCaptureManager.getInstance().discardSession(server, project.id().toString());
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "draft-discarded",
                "Recovery draft was discarded",
                "",
                ""
        ));
        LumaMod.LOGGER.info("Discarded recovery draft for project {}", project.name());
    }

    public boolean hasDraft(MinecraftServer server, String projectName) throws IOException {
        return this.loadDraft(server, projectName).isPresent();
    }

    private List<PreparedChunkBatch> decodeDraft(
            ServerLevel level,
            List<StoredBlockChange> changes,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        Map<String, List<PreparedBlockPlacement>> grouped = new LinkedHashMap<>();
        int index = 0;
        for (StoredBlockChange change : changes) {
            String chunkKey = (change.pos().x() >> 4) + ":" + (change.pos().z() >> 4);
            grouped.computeIfAbsent(chunkKey, ignored -> new ArrayList<>())
                    .add(new PreparedBlockPlacement(
                            new BlockPos(change.pos().x(), change.pos().y(), change.pos().z()),
                            io.github.luma.minecraft.world.BlockStateNbtCodec.deserializeBlockState(level, change.newValue().stateTag()),
                            change.newValue().blockEntityTag() == null ? null : change.newValue().blockEntityTag().copy()
                    ));
            index += 1;
            progressSink.update(OperationStage.PREPARING, index, changes.size(), "Decoded recovery draft");
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (Map.Entry<String, List<PreparedBlockPlacement>> entry : grouped.entrySet()) {
            String[] split = entry.getKey().split(":", 2);
            batches.add(new PreparedChunkBatch(
                    new io.github.luma.domain.model.ChunkPoint(Integer.parseInt(split[0]), Integer.parseInt(split[1])),
                    List.copyOf(entry.getValue())
            ));
        }
        LumaDebugLog.log(
                "recovery",
                "Decoded recovery draft with {} changes into {} chunk batches",
                changes.size(),
                batches.size()
        );
        return batches;
    }
}
