package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockChangeRecord;
import io.github.luma.domain.model.ChangeSession;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.BlockChangeApplier;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.RecoveryRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class RecoveryService {

    private final ProjectService projectService = new ProjectService();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();

    public Optional<RecoveryDraft> loadDraft(MinecraftServer server, String projectName) throws IOException {
        return this.recoveryRepository.loadDraft(this.projectService.resolveLayout(server, projectName));
    }

    public List<RecoveryJournalEntry> loadJournal(MinecraftServer server, String projectName) throws IOException {
        return this.recoveryRepository.loadJournal(this.projectService.resolveLayout(server, projectName));
    }

    public void saveSessionDraft(ProjectLayout layout, ChangeSession session) throws IOException {
        this.recoveryRepository.saveDraft(layout, new RecoveryDraft(
                session.projectId(),
                session.variantId(),
                session.baseVersionId(),
                session.actor(),
                session.mutationSource(),
                session.startedAt(),
                session.updatedAt(),
                session.orderedChanges()
        ));
    }

    public ChangeSession mergeDraft(ChangeSession session, RecoveryDraft draft) {
        ChangeSession merged = ChangeSession.create(
                session.id(),
                session.projectId(),
                session.variantId(),
                session.baseVersionId(),
                session.actor(),
                session.mutationSource(),
                draft.startedAt()
        );

        for (BlockChangeRecord change : draft.changes()) {
            merged = merged.addChange(change, draft.updatedAt());
        }
        for (BlockChangeRecord change : session.orderedChanges()) {
            merged = merged.addChange(change, session.updatedAt());
        }
        return merged;
    }

    public RecoveryDraft restoreDraft(ServerLevel level, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        RecoveryDraft draft = this.recoveryRepository.loadDraft(layout)
                .orElseThrow(() -> new IllegalArgumentException("No recovery draft available for " + projectName));

        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            try {
                BlockChangeApplier.applyChanges(level, draft.changes());
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });

        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "draft-restored",
                "Recovered draft changes were applied back to the world",
                "",
                draft.variantId()
        ));
        return draft;
    }

    public void discardDraft(MinecraftServer server, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        this.recoveryRepository.deleteDraft(layout);
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "draft-discarded",
                "Recovery draft was discarded",
                "",
                ""
        ));
    }

    public boolean hasDraft(MinecraftServer server, String projectName) throws IOException {
        return this.loadDraft(server, projectName).isPresent();
    }
}
