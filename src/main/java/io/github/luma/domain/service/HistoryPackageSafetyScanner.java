package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.HistoryPackageSafetyReport;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;

/**
 * Scans imported history payloads for world-state data that should require an
 * explicit trust decision before apply.
 */
public final class HistoryPackageSafetyScanner {

    private static final Set<String> DANGEROUS_BLOCK_IDS = Set.of(
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:structure_block",
            "minecraft:jigsaw",
            "minecraft:spawner"
    );
    private static final Set<String> DANGEROUS_BLOCK_ENTITY_IDS = Set.of(
            "minecraft:command_block",
            "minecraft:structure_block",
            "minecraft:jigsaw",
            "minecraft:mob_spawner",
            "minecraft:spawner"
    );
    private static final Set<String> DANGEROUS_ENTITY_IDS = Set.of(
            "minecraft:command_block_minecart"
    );

    private final PatchMetaRepository patchMetaRepository;
    private final PatchDataRepository patchDataRepository;
    private final SnapshotReader snapshotReader;
    private final HistoryPayloadTypeRegistry typeRegistry;

    public HistoryPackageSafetyScanner() {
        this(
                new PatchMetaRepository(),
                new PatchDataRepository(),
                new SnapshotReader(),
                new MinecraftHistoryPayloadTypeRegistry()
        );
    }

    HistoryPackageSafetyScanner(
            PatchMetaRepository patchMetaRepository,
            PatchDataRepository patchDataRepository,
            SnapshotReader snapshotReader
    ) {
        this(patchMetaRepository, patchDataRepository, snapshotReader, new MinecraftHistoryPayloadTypeRegistry());
    }

    HistoryPackageSafetyScanner(
            PatchMetaRepository patchMetaRepository,
            PatchDataRepository patchDataRepository,
            SnapshotReader snapshotReader,
            HistoryPayloadTypeRegistry typeRegistry
    ) {
        this.patchMetaRepository = patchMetaRepository;
        this.patchDataRepository = patchDataRepository;
        this.snapshotReader = snapshotReader;
        this.typeRegistry = typeRegistry;
    }

    public HistoryPackageSafetyReport scanMergePlan(VariantMergePlan plan) {
        if (plan == null) {
            return HistoryPackageSafetyReport.clean();
        }
        return this.scanChanges(plan.mergeChanges(), plan.mergeEntityChanges());
    }

    public HistoryPackageSafetyReport scanProjectHistory(
            ProjectLayout layout,
            List<ProjectVersion> versions
    ) throws IOException {
        SafetyAccumulator accumulator = new SafetyAccumulator();
        Set<String> scannedPatchIds = new LinkedHashSet<>();
        Set<String> scannedSnapshotIds = new LinkedHashSet<>();
        Set<String> scannedEntityCheckpointIds = new LinkedHashSet<>();
        for (ProjectVersion version : versions == null ? List.<ProjectVersion>of() : versions) {
            for (String patchId : version.patchIds()) {
                if (patchId == null || patchId.isBlank() || !scannedPatchIds.add(patchId)) {
                    continue;
                }
                var metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IOException("Patch metadata is missing for " + patchId));
                PatchWorldChanges changes = this.patchDataRepository.loadWorldChanges(layout, metadata);
                this.scanBlockChanges(changes.blockChanges(), accumulator);
                this.scanEntityChanges(changes.entityChanges(), accumulator);
            }
            String snapshotId = version.snapshotId();
            if (snapshotId != null && !snapshotId.isBlank() && scannedSnapshotIds.add(snapshotId)) {
                this.scanSnapshot(this.snapshotReader.readFile(layout.snapshotFile(snapshotId)), accumulator);
            }
            String entityCheckpointId = version.entityCheckpointId();
            if (entityCheckpointId != null
                    && !entityCheckpointId.isBlank()
                    && scannedEntityCheckpointIds.add(entityCheckpointId)) {
                this.scanSnapshot(this.snapshotReader.readFile(layout.entityCheckpointFile(entityCheckpointId)), accumulator);
            }
        }
        return accumulator.toReport();
    }

    public HistoryPackageSafetyReport scanChanges(
            Collection<StoredBlockChange> blockChanges,
            Collection<StoredEntityChange> entityChanges
    ) {
        SafetyAccumulator accumulator = new SafetyAccumulator();
        this.scanBlockChanges(blockChanges, accumulator);
        this.scanEntityChanges(entityChanges, accumulator);
        return accumulator.toReport();
    }

    private void scanSnapshot(SnapshotData snapshot, SafetyAccumulator accumulator) {
        if (snapshot == null || snapshot.chunks() == null) {
            return;
        }
        for (SnapshotChunkData chunk : snapshot.chunks()) {
            for (SnapshotSectionData section : chunk.sections()) {
                for (CompoundTag stateTag : section.palette()) {
                    this.scanBlockStateTag(stateTag, accumulator);
                }
            }
            for (CompoundTag blockEntityTag : chunk.blockEntities().values()) {
                this.scanBlockEntityTag(blockEntityTag, accumulator);
            }
            for (EntityPayload entityPayload : chunk.entitySnapshots()) {
                this.scanEntityPayload(entityPayload, accumulator);
            }
        }
    }

    private void scanBlockChanges(Collection<StoredBlockChange> changes, SafetyAccumulator accumulator) {
        if (changes == null) {
            return;
        }
        for (StoredBlockChange change : changes) {
            if (change == null) {
                continue;
            }
            this.scanStatePayload(change.oldValue(), accumulator);
            this.scanStatePayload(change.newValue(), accumulator);
        }
    }

    private void scanStatePayload(StatePayload payload, SafetyAccumulator accumulator) {
        if (payload == null) {
            return;
        }
        this.scanBlockStateTag(payload.stateTag(), accumulator);
        this.scanBlockEntityTag(payload.blockEntityTag(), accumulator);
    }

    private void scanBlockStateTag(CompoundTag stateTag, SafetyAccumulator accumulator) {
        if (stateTag == null) {
            return;
        }
        String blockId = stateTag.getString("Name").orElse("");
        if (DANGEROUS_BLOCK_IDS.contains(blockId)) {
            accumulator.addBlockEntityType(blockId);
        }
    }

    private void scanBlockEntityTag(CompoundTag blockEntityTag, SafetyAccumulator accumulator) {
        if (blockEntityTag == null) {
            return;
        }
        String id = blockEntityTag.getString("id").orElse("");
        if (id.isBlank()) {
            accumulator.addBlockEntityType("unknown:block_entity");
            return;
        }
        if (DANGEROUS_BLOCK_ENTITY_IDS.contains(id) || !this.knownBlockEntityId(id)) {
            accumulator.addBlockEntityType(id);
        }
    }

    private void scanEntityChanges(Collection<StoredEntityChange> changes, SafetyAccumulator accumulator) {
        if (changes == null) {
            return;
        }
        for (StoredEntityChange change : changes) {
            if (change == null) {
                continue;
            }
            this.scanEntityId(change.entityType(), accumulator);
            this.scanEntityPayload(change.oldValue(), accumulator);
            this.scanEntityPayload(change.newValue(), accumulator);
        }
    }

    private void scanEntityPayload(EntityPayload payload, SafetyAccumulator accumulator) {
        if (payload == null) {
            return;
        }
        this.scanEntityId(payload.entityType(), accumulator);
    }

    private void scanEntityId(String id, SafetyAccumulator accumulator) {
        if (id == null || id.isBlank()) {
            accumulator.addEntityType("unknown:entity");
            return;
        }
        if (DANGEROUS_ENTITY_IDS.contains(id) || !this.knownEntityId(id)) {
            accumulator.addEntityType(id);
        }
    }

    private boolean knownBlockEntityId(String id) {
        try {
            return this.typeRegistry.knownBlockEntityId(id);
        } catch (RuntimeException | LinkageError exception) {
            LumaMod.LOGGER.warn("History package block entity registry lookup failed for {}", id, exception);
            return false;
        }
    }

    private boolean knownEntityId(String id) {
        try {
            return this.typeRegistry.knownEntityId(id);
        } catch (RuntimeException | LinkageError exception) {
            LumaMod.LOGGER.warn("History package entity registry lookup failed for {}", id, exception);
            return false;
        }
    }

    private static final class SafetyAccumulator {

        private final LinkedHashSet<String> blockEntityTypes = new LinkedHashSet<>();
        private final LinkedHashSet<String> entityTypes = new LinkedHashSet<>();

        private void addBlockEntityType(String id) {
            this.blockEntityTypes.add(id);
        }

        private void addEntityType(String id) {
            this.entityTypes.add(id);
        }

        private HistoryPackageSafetyReport toReport() {
            if (this.blockEntityTypes.isEmpty() && this.entityTypes.isEmpty()) {
                return HistoryPackageSafetyReport.clean();
            }
            return HistoryPackageSafetyReport.unsafe(
                    List.copyOf(this.blockEntityTypes),
                    List.copyOf(this.entityTypes)
            );
        }
    }
}
