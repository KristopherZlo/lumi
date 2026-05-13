package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestorePayloadLoaderTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    @TempDir
    Path tempDir;

    private final RestorePayloadLoader loader = new RestorePayloadLoader();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();

    @Test
    void loadsSelectedChunkWorldChangesThroughVersionPatchMetadata() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));
        String entityId = "00000000-0000-0000-0000-000000000071";
        PatchMetadata metadata = this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(
                        change(1, "minecraft:stone", "minecraft:gold_block"),
                        change(33, "minecraft:dirt", "minecraft:diamond_block")
                ),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        entity(entityId, 33.0D),
                        entity(entityId, 34.0D)
                ))
        );
        this.patchMetaRepository.save(layout, metadata);

        PatchWorldChanges selected = this.loader.loadVersionWorldChanges(
                layout,
                List.of(version("v0002", List.of("patch-0002"))),
                List.of(new ChunkPoint(2, 0))
        );

        assertEquals(List.of(new BlockPoint(33, 64, 1)), selected.blockChanges().stream()
                .map(StoredBlockChange::pos)
                .toList());
        assertEquals(List.of(entityId), selected.entityChanges().stream()
                .map(StoredEntityChange::entityId)
                .toList());
    }

    @Test
    void loadsEntityChangesByEntityIdThroughVersionPatchMetadata() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));
        String entityId = "00000000-0000-0000-0000-000000000072";
        PatchMetadata metadata = this.patchDataRepository.writePayload(
                layout,
                "patch-entity",
                "project",
                "v0002",
                List.of(),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        entity(entityId, 1.0D),
                        entity(entityId, 2.0D)
                ))
        );
        this.patchMetaRepository.save(layout, metadata);

        List<StoredEntityChange> entityChanges = this.loader.loadVersionEntityChanges(
                layout,
                version("v0002", List.of("patch-entity")),
                Set.of(entityId)
        );

        assertEquals(List.of(entityId), entityChanges.stream().map(StoredEntityChange::entityId).toList());
    }

    private static ProjectVersion version(String id, List<String> patchIds) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                "v0001",
                "",
                patchIds,
                VersionKind.MANUAL,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW
        );
    }

    private static StoredBlockChange change(int x, String oldBlock, String newBlock) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 1),
                payload(oldBlock),
                payload(newBlock)
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }

    private static EntityPayload entity(String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.putString("UUID", entityId);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
