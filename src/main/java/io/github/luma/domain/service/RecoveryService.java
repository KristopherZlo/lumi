package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryDraftSummary;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.world.EntityBatch;
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
                draft.totalChangeCount()
        );
        LumaDebugLog.log(
                project,
                "recovery",
                "Starting recovery restore for project {} with {} changes from {}",
                project.name(),
                draft.totalChangeCount(),
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
                    progressSink.update(OperationStage.PREPARING, 0, draft.totalChangeCount(), "Decoding recovery draft");
                    List<PreparedChunkBatch> batches = this.decodeDraft(level, draft, progressSink);
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
                                        draft.totalChangeCount()
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
            RecoveryDraft draft,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        List<StoredBlockChange> changes = draft.changes();
        List<StoredEntityChange> entityChanges = draft.entityChanges();
        Map<io.github.luma.domain.model.ChunkPoint, List<PreparedBlockPlacement>> grouped = new LinkedHashMap<>();
        Map<io.github.luma.domain.model.ChunkPoint, List<StoredEntityChange>> groupedEntities = new LinkedHashMap<>();
        int index = 0;
        for (StoredBlockChange change : changes) {
            io.github.luma.domain.model.ChunkPoint chunk = new io.github.luma.domain.model.ChunkPoint(
                    change.pos().x() >> 4,
                    change.pos().z() >> 4
            );
            grouped.computeIfAbsent(chunk, ignored -> new ArrayList<>())
                    .add(new PreparedBlockPlacement(
                            new BlockPos(change.pos().x(), change.pos().y(), change.pos().z()),
                            io.github.luma.minecraft.world.BlockStateNbtCodec.deserializeBlockState(level, change.newValue().stateTag()),
                            change.newValue().blockEntityTag() == null ? null : change.newValue().blockEntityTag().copy()
                    ));
            index += 1;
            progressSink.update(OperationStage.PREPARING, index, draft.totalChangeCount(), "Decoded recovery draft");
        }
        for (StoredEntityChange change : entityChanges) {
            groupedEntities.computeIfAbsent(change.chunk(), ignored -> new ArrayList<>()).add(change);
            index += 1;
            progressSink.update(OperationStage.PREPARING, index, draft.totalChangeCount(), "Decoded recovery draft");
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        java.util.LinkedHashSet<io.github.luma.domain.model.ChunkPoint> chunks = new java.util.LinkedHashSet<>();
        chunks.addAll(grouped.keySet());
        chunks.addAll(groupedEntities.keySet());
        for (io.github.luma.domain.model.ChunkPoint chunk : chunks) {
            batches.add(new PreparedChunkBatch(
                    chunk,
                    List.copyOf(grouped.getOrDefault(chunk, List.of())),
                    toEntityBatch(groupedEntities.getOrDefault(chunk, List.of()))
            ));
        }
        LumaDebugLog.log(
                "recovery",
                "Decoded recovery draft with {} block and {} entity changes into {} chunk batches",
                changes.size(),
                entityChanges.size(),
                batches.size()
        );
        return batches;
    }

    private static EntityBatch toEntityBatch(List<StoredEntityChange> changes) {
        List<net.minecraft.nbt.CompoundTag> spawns = new ArrayList<>();
        List<String> removals = new ArrayList<>();
        List<net.minecraft.nbt.CompoundTag> updates = new ArrayList<>();
        for (StoredEntityChange change : changes) {
            if (change.isSpawn()) {
                spawns.add(change.newValue().copyTag());
            } else if (change.isRemove()) {
                removals.add(change.entityId());
            } else if (change.isUpdate()) {
                updates.add(change.newValue().copyTag());
            }
        }
        return new EntityBatch(spawns, removals, updates);
    }
}
