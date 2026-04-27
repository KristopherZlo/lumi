package io.github.luma.storage.repository;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import io.github.luma.LumaMod;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

public final class RecoveryRepository {

    private static final int DRAFT_MAGIC = 0x4C445246;
    private static final int DRAFT_VERSION = 4;
    private static final long WAL_COMPACT_THRESHOLD_BYTES = 512 * 1024;
    private static final Type JOURNAL_TYPE = new TypeToken<List<RecoveryJournalEntry>>() { }.getType();

    public void saveDraft(ProjectLayout layout, RecoveryDraft draft) throws IOException {
        this.appendWalEntry(layout.recoveryWalFile(), draft);
        if (this.shouldCompact(layout)) {
            this.writeBase(layout.recoveryBaseFile(), draft);
            Files.deleteIfExists(layout.recoveryWalFile());
            LumaMod.LOGGER.info(
                    "Compacted recovery WAL for project storage {} with {} changes",
                    layout.root().getFileName(),
                    draft.totalChangeCount()
            );
        }
    }

    public Optional<RecoveryDraft> loadDraft(ProjectLayout layout) throws IOException {
        RecoveryDraft latest = null;
        try {
            if (Files.exists(layout.recoveryBaseFile())) {
                latest = this.readSingleEntry(layout.recoveryBaseFile());
            }
            if (Files.exists(layout.recoveryWalFile())) {
                RecoveryDraft walLatest = this.readLastWalEntry(layout.recoveryWalFile());
                if (walLatest != null) {
                    latest = walLatest;
                }
            }
            return Optional.ofNullable(latest);
        } catch (IOException exception) {
            if (Files.exists(layout.recoveryWalFile())) {
                StorageIo.quarantineCorruptedFile(layout.recoveryWalFile(), exception);
            } else if (Files.exists(layout.recoveryBaseFile())) {
                StorageIo.quarantineCorruptedFile(layout.recoveryBaseFile(), exception);
            }
            return Optional.empty();
        }
    }

    public void saveOperationDraft(ProjectLayout layout, RecoveryDraft draft) throws IOException {
        this.writeBase(layout.recoveryOperationDraftFile(), draft);
        LumaMod.LOGGER.info(
                "Saved operation draft for project storage {} with {} changes",
                layout.root().getFileName(),
                draft.totalChangeCount()
        );
    }

    public Optional<RecoveryDraft> loadOperationDraft(ProjectLayout layout) throws IOException {
        try {
            if (!Files.exists(layout.recoveryOperationDraftFile())) {
                return Optional.empty();
            }
            return Optional.of(this.readSingleEntry(layout.recoveryOperationDraftFile()));
        } catch (IOException exception) {
            StorageIo.quarantineCorruptedFile(layout.recoveryOperationDraftFile(), exception);
            return Optional.empty();
        }
    }

    public void deleteOperationDraft(ProjectLayout layout) throws IOException {
        Files.deleteIfExists(layout.recoveryOperationDraftFile());
        LumaMod.LOGGER.info("Deleted operation draft storage for {}", layout.root().getFileName());
    }

    public void deleteDraft(ProjectLayout layout) throws IOException {
        Files.deleteIfExists(layout.recoveryBaseFile());
        Files.deleteIfExists(layout.recoveryWalFile());
        LumaMod.LOGGER.info("Deleted recovery draft storage for {}", layout.root().getFileName());
    }

    public List<RecoveryJournalEntry> loadJournal(ProjectLayout layout) throws IOException {
        Path journalFile = layout.recoveryJournalFile();
        if (!Files.exists(journalFile)) {
            return List.of();
        }

        try (Reader reader = Files.newBufferedReader(journalFile, StandardCharsets.UTF_8)) {
            List<RecoveryJournalEntry> entries = GsonProvider.gson().fromJson(reader, JOURNAL_TYPE);
            return entries == null ? List.of() : entries;
        } catch (JsonSyntaxException exception) {
            StorageIo.quarantineCorruptedFile(journalFile, exception);
            return List.of();
        }
    }

    public void appendJournalEntry(ProjectLayout layout, RecoveryJournalEntry entry) throws IOException {
        List<RecoveryJournalEntry> entries = new ArrayList<>(this.loadJournal(layout));
        entries.add(entry);
        StorageIo.writeAtomically(layout.recoveryJournalFile(), output -> output.write(
                GsonProvider.compactGson().toJson(entries).getBytes(StandardCharsets.UTF_8)
        ));
    }

    private boolean shouldCompact(ProjectLayout layout) throws IOException {
        return !Files.exists(layout.recoveryBaseFile())
                || (Files.exists(layout.recoveryWalFile()) && Files.size(layout.recoveryWalFile()) >= WAL_COMPACT_THRESHOLD_BYTES);
    }

    private void writeBase(Path baseFile, RecoveryDraft draft) throws IOException {
        StorageIo.writeAtomically(baseFile, output -> {
            byte[] entryBytes = this.serializeDraft(draft);
            try (DataOutputStream data = new DataOutputStream(new LZ4FrameOutputStream(new BufferedOutputStream(output)))) {
                data.writeInt(entryBytes.length);
                data.write(entryBytes);
            }
        });
    }

    private void appendWalEntry(Path walFile, RecoveryDraft draft) throws IOException {
        Files.createDirectories(walFile.getParent());
        byte[] compressedEntry = this.compressEntry(this.serializeDraft(draft));
        try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(
                walFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
        ))) {
            data.writeInt(compressedEntry.length);
            data.write(compressedEntry);
        }
    }

    private RecoveryDraft readSingleEntry(Path baseFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(baseFile))
        ))) {
            int length = input.readInt();
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return this.deserializeDraft(bytes);
        }
    }

    private RecoveryDraft readLastWalEntry(Path walFile) throws IOException {
        RecoveryDraft latest = null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(walFile)))) {
            while (input.available() > 0) {
                int length = input.readInt();
                byte[] bytes = new byte[length];
                input.readFully(bytes);
                latest = this.deserializeDraft(this.decompressEntry(bytes));
            }
        }
        return latest;
    }

    private byte[] compressEntry(byte[] entryBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream output = new LZ4FrameOutputStream(new BufferedOutputStream(buffer))) {
            output.write(entryBytes);
        }
        return buffer.toByteArray();
    }

    private byte[] decompressEntry(byte[] compressedBytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(new ByteArrayInputStream(compressedBytes))
        ))) {
            return input.readAllBytes();
        }
    }

    private byte[] serializeDraft(RecoveryDraft draft) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(DRAFT_MAGIC);
            output.writeInt(DRAFT_VERSION);
            output.writeUTF(nullToEmpty(draft.projectId()));
            output.writeUTF(nullToEmpty(draft.variantId()));
            output.writeUTF(nullToEmpty(draft.baseVersionId()));
            output.writeUTF(nullToEmpty(draft.actor()));
            output.writeUTF(draft.mutationSource().name());
            output.writeLong(draft.startedAt().toEpochMilli());
            output.writeLong(draft.updatedAt().toEpochMilli());
            output.writeInt(draft.changes().size());
            for (StoredBlockChange change : draft.changes()) {
                StatePayload oldValue = this.normalizePayload(change.oldValue());
                StatePayload newValue = this.normalizePayload(change.newValue());
                output.writeInt(change.pos().x());
                output.writeInt(change.pos().y());
                output.writeInt(change.pos().z());
                StorageIo.writeCompound(output, oldValue.stateTag());
                StorageIo.writeNullableCompound(output, oldValue.blockEntityTag());
                StorageIo.writeCompound(output, newValue.stateTag());
                StorageIo.writeNullableCompound(output, newValue.blockEntityTag());
            }
            output.writeInt(draft.entityChanges().size());
            for (StoredEntityChange change : draft.entityChanges()) {
                output.writeUTF(nullToEmpty(change.entityId()));
                output.writeUTF(nullToEmpty(change.entityType()));
                StorageIo.writeNullableCompound(output, change.oldValue() == null ? null : change.oldValue().copyTag());
                StorageIo.writeNullableCompound(output, change.newValue() == null ? null : change.newValue().copyTag());
            }
        }
        return buffer.toByteArray();
    }

    private RecoveryDraft deserializeDraft(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != DRAFT_MAGIC || (version != 3 && version != DRAFT_VERSION)) {
                throw new IOException("Unsupported recovery draft format");
            }

            String projectId = input.readUTF();
            String variantId = input.readUTF();
            String baseVersionId = input.readUTF();
            String actor = input.readUTF();
            WorldMutationSource mutationSource = WorldMutationSource.valueOf(input.readUTF());
            Instant startedAt = Instant.ofEpochMilli(input.readLong());
            Instant updatedAt = Instant.ofEpochMilli(input.readLong());
            int changeCount = input.readInt();
            List<StoredBlockChange> changes = new ArrayList<>();
            for (int index = 0; index < changeCount; index++) {
                int x = input.readInt();
                int y = input.readInt();
                int z = input.readInt();
                changes.add(new StoredBlockChange(
                        new io.github.luma.domain.model.BlockPoint(x, y, z),
                        new StatePayload(StorageIo.readCompound(input), StorageIo.readNullableCompound(input)),
                        new StatePayload(StorageIo.readCompound(input), StorageIo.readNullableCompound(input))
                ));
            }
            List<StoredEntityChange> entityChanges = new ArrayList<>();
            if (version >= 4) {
                int entityChangeCount = input.readInt();
                for (int index = 0; index < entityChangeCount; index++) {
                    String entityId = input.readUTF();
                    String entityType = input.readUTF();
                    net.minecraft.nbt.CompoundTag oldTag = StorageIo.readNullableCompound(input);
                    net.minecraft.nbt.CompoundTag newTag = StorageIo.readNullableCompound(input);
                    entityChanges.add(new StoredEntityChange(
                            entityId,
                            entityType,
                            oldTag == null ? null : new EntityPayload(oldTag),
                            newTag == null ? null : new EntityPayload(newTag)
                    ));
                }
            }
            return new RecoveryDraft(
                    projectId,
                    variantId,
                    baseVersionId,
                    actor,
                    mutationSource,
                    startedAt,
                    updatedAt,
                    changes,
                    entityChanges
            );
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private StatePayload normalizePayload(StatePayload payload) {
        return payload == null ? StatePayload.air() : payload;
    }
}
