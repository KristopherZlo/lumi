package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.WorldActionRecord;
import io.github.lumi.domain.model.WorldActionRecord.BlockDelta;
import io.github.lumi.domain.model.WorldActionRecord.EntityDelta;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.model.WorldActionCheckpoint;
import io.github.lumi.domain.model.WorldActionScope;
import io.github.lumi.domain.model.WorldActionIndex;
import io.github.lumi.domain.model.ActionReplayTarget;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.SectionKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.lumi.storage.repository.WorldActionHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldActionPayloadCodecTest {
    @Test
    void publishesOnlyCompleteRootsAndVerifiesCompressedPayloads(@TempDir Path root) throws IOException {
        var repository = new WorldActionHistoryRepository(root);
        var version = new WorldDataVersion(5000);
        var record = new WorldActionRecord(metadata(1), new WorldActionRecord.Payload(Map.of(), Map.of()));
        UUID blob = repository.writePayload(record, version);
        var index = new WorldActionIndex(1, 1, 0, List.of(record.metadata()), Map.of(record.metadata().actor(),
                new WorldActionIndex.Stacks(List.of(record.metadata().id()), List.of())));
        var state = new WorldActionIndexCodec.State(UUID.randomUUID(), version, UUID.randomUUID(),
                new BranchName("main"), index, Map.of(record.metadata().id(), blob), Optional.empty());
        repository.writeIndex(state);
        var reopened = new WorldActionHistoryRepository(root);
        assertEquals(state, reopened.readIndex().orElseThrow());
        assertEquals(record, reopened.readPayload(metadata(1), blob, version, 4096));
        var incomplete = new WorldActionIndexCodec.State(state.epoch(), version, state.workspace(), state.branch(),
                index, Map.of(record.metadata().id(), UUID.randomUUID()), Optional.empty());
        assertThrows(IOException.class, () -> reopened.writeIndex(incomplete));
        assertEquals(state, reopened.readIndex().orElseThrow());
        UUID orphan = repository.writePayload(record, version);
        repository.removeUnreferencedPayloads();
        assertThrows(IOException.class, () -> repository.readPayload(metadata(1), orphan, version, 4096));
        Path payload = root.resolve("actions/payloads/" + blob + ".bin.gz");
        byte[] broken = Files.readAllBytes(payload);
        broken[broken.length - 5] ^= 1;
        Files.write(payload, broken);
        assertThrows(IOException.class, () -> repository.readPayload(metadata(1), blob, version, 4096));
    }

    @Test
    void roundTripsTheCursorWithCheckpointOwnershipAndRejectsCorruption() throws IOException {
        var before = CommitId.hash(new byte[] {1});
        var after = CommitId.hash(new byte[] {2});
        long[] mask = new long[64];
        mask[63] = Long.MIN_VALUE;
        var scope = WorldActionScope.fromMasks(Map.of(new SectionKey(-1, -1, -1), mask), Set.of(new UUID(0, 9)));
        mask[63] = 0;
        var checkpoint = new WorldActionCheckpoint(new BranchName("main"), before, after,
                new BranchRef(new BranchName("hidden/session-undo/test"), before, 0), scope);
        var second = new WorldActionRecord.Metadata(new UUID(0, 3), new UUID(0, 2), 2, new UUID(0, 3),
                1, false, true, false, Optional.of(checkpoint));
        var index = new WorldActionIndex(7, 2, 0, List.of(metadata(1), second),
                Map.of(new UUID(0, 2), new WorldActionIndex.Stacks(List.of(new UUID(0, 1)), List.of(second.id()))));
        var state = new WorldActionIndexCodec.State(new UUID(0, 100), new WorldDataVersion(5000),
                new UUID(0, 200), new BranchName("main"), index, Map.of(new UUID(0, 1), new UUID(0, 300)),
                Optional.of(new WorldActionIndexCodec.Publication(new UUID(0, 400),
                        new ActionReplayTarget(second.actor(), second.id(), true), true)));
        var bytes = new ByteArrayOutputStream();
        var codec = new WorldActionIndexCodec();
        codec.encode(state, new DataOutputStream(bytes));
        byte[] valid = bytes.toByteArray();
        var restored = codec.decode(input(valid));
        assertEquals(state, restored);
        assertEquals(true, restored.index().actions().getLast().checkpoint().orElseThrow().scope()
                .intersects(Set.of(new BlockPosition(-1, -1, -1)), Set.of()));
        byte[] invalidFlag = valid.clone();
        invalidFlag[invalidFlag.length - 1] = 2;
        byte[] oversizedCount = valid.clone();
        ByteBuffer.wrap(oversizedCount).putInt(72, Integer.MAX_VALUE);
        for (byte[] invalid : new byte[][] {invalidFlag, oversizedCount,
                Arrays.copyOf(valid, valid.length - 1), Arrays.copyOf(valid, valid.length + 1)}) {
            assertThrows(IOException.class, () -> codec.decode(input(invalid)));
        }
    }

    @Test
    void roundTripsOrderedWorldEndpointsAndRejectsInvalidInputsBeforeAllocation() throws IOException {
        var version = new WorldDataVersion(5000);
        var codec = new WorldActionPayloadCodec();
        var before = new BlockSnapshot("minecraft:stone", Optional.of(new CanonicalNbt(new byte[] {1, 2})));
        var after = new BlockSnapshot("minecraft:gold_block", Optional.empty());
        var blocks = new LinkedHashMap<BlockPosition, BlockDelta>();
        blocks.put(new BlockPosition(16, 1, 0), new BlockDelta(before, after));
        blocks.put(new BlockPosition(-1, -1, -1), new BlockDelta(after, before));
        UUID entity = new UUID(0, 9);
        var payload = new WorldActionRecord.Payload(blocks, Map.of(entity, new EntityDelta(Optional.empty(),
                Optional.of(new EntityState(entity, "minecraft:armor_stand", new CanonicalNbt(new byte[] {3, 4}))))));
        var record = new WorldActionRecord(metadata(1), payload);
        var bytes = new ByteArrayOutputStream();
        codec.encode(record, version, new DataOutputStream(bytes));
        byte[] valid = bytes.toByteArray();

        var restored = codec.decode(metadata(1), version, 4096, input(valid));
        assertEquals(record, restored);
        assertEquals(List.copyOf(blocks.keySet()), List.copyOf(restored.payload().blocks().keySet()));
        assertThrows(IOException.class, () -> codec.decode(metadata(2), version, 4096, input(valid)));
        assertThrows(IOException.class, () -> codec.decode(metadata(1), new WorldDataVersion(5001), 4096, input(valid)));
        assertThrows(IOException.class, () -> codec.decode(metadata(1), version, 1, input(valid)));
        assertThrows(IOException.class, () -> codec.decode(metadata(1), version, 4096,
                input(Arrays.copyOf(valid, valid.length - 1))));
        assertThrows(IOException.class, () -> codec.decode(metadata(1), version, 4096,
                input(Arrays.copyOf(valid, valid.length + 1))));
        byte[] oversizedPalette = valid.clone();
        ByteBuffer.wrap(oversizedPalette).putInt(32, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> codec.decode(metadata(1), version, 4096, input(oversizedPalette)));
    }

    private static WorldActionRecord.Metadata metadata(long revision) {
        return new WorldActionRecord.Metadata(new UUID(0, 1), new UUID(0, 2), 1, new UUID(0, 1),
                revision, true, true, false, Optional.empty());
    }

    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}
