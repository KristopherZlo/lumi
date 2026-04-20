package io.github.luma.storage.repository;

import com.google.gson.reflect.TypeToken;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RecoveryRepository {

    private static final java.lang.reflect.Type JOURNAL_TYPE = new TypeToken<List<RecoveryJournalEntry>>() { }.getType();

    public void saveDraft(ProjectLayout layout, RecoveryDraft draft) throws IOException {
        Files.createDirectories(layout.recoveryDir());
        Files.writeString(layout.recoveryDraftFile(), GsonProvider.gson().toJson(draft), StandardCharsets.UTF_8);
    }

    public Optional<RecoveryDraft> loadDraft(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.recoveryDraftFile())) {
            return Optional.empty();
        }

        return Optional.ofNullable(GsonProvider.gson().fromJson(Files.readString(layout.recoveryDraftFile()), RecoveryDraft.class));
    }

    public void deleteDraft(ProjectLayout layout) throws IOException {
        Files.deleteIfExists(layout.recoveryDraftFile());
    }

    public List<RecoveryJournalEntry> loadJournal(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.recoveryJournalFile())) {
            return List.of();
        }

        List<RecoveryJournalEntry> entries = GsonProvider.gson().fromJson(Files.readString(layout.recoveryJournalFile()), JOURNAL_TYPE);
        return entries == null ? List.of() : entries;
    }

    public void appendJournalEntry(ProjectLayout layout, RecoveryJournalEntry entry) throws IOException {
        Files.createDirectories(layout.recoveryDir());
        List<RecoveryJournalEntry> entries = new ArrayList<>(this.loadJournal(layout));
        entries.add(entry);
        Files.writeString(layout.recoveryJournalFile(), GsonProvider.gson().toJson(entries), StandardCharsets.UTF_8);
    }
}
