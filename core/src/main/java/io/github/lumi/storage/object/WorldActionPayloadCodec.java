package io.github.lumi.storage.object;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.WorldActionRecord;
import io.github.lumi.domain.model.WorldActionRecord.BlockDelta;
import io.github.lumi.domain.model.WorldActionRecord.EntityDelta;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.model.BiomePosition;
import io.github.lumi.domain.model.WorldActionRecord.BiomeDelta;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Streamed action payload with a shared state palette and a bounded decoder. */
public final class WorldActionPayloadCodec {
    private static final int MAGIC = 0x4C415032;
    private static final int MAX_STRING = 1024 * 1024;
    private static final int MAX_NBT = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4_000_000;

    public void encode(WorldActionRecord record, WorldDataVersion version, DataOutputStream output) throws IOException {
        if (record.payload().blocks().size() > MAX_ENTRIES || record.payload().entities().size() > MAX_ENTRIES) {
            throw new IOException("Too many action payload entries");
        }
        output.writeInt(MAGIC);
        CanonicalBytes.writeUuid(output, record.metadata().id());
        output.writeLong(record.metadata().revision());
        output.writeInt(version.value());
        var palette = new LinkedHashMap<String, Integer>();
        record.payload().blocks().values().forEach(delta -> {
            palette.computeIfAbsent(delta.before().blockState(), ignored -> palette.size());
            palette.computeIfAbsent(delta.after().blockState(), ignored -> palette.size());
        });
        if (palette.size() > MAX_ENTRIES) throw new IOException("Action block palette is too large");
        output.writeInt(palette.size());
        for (String state : palette.keySet()) CanonicalBytes.writeString(output, state, MAX_STRING, "block state");
        output.writeInt(record.payload().blocks().size());
        for (var entry : record.payload().blocks().entrySet()) {
            output.writeInt(entry.getKey().x());
            output.writeInt(entry.getKey().y());
            output.writeInt(entry.getKey().z());
            writeBlock(output, entry.getValue().before(), palette);
            writeBlock(output, entry.getValue().after(), palette);
        }
        output.writeInt(record.payload().entities().size());
        for (var entry : record.payload().entities().entrySet()) {
            CanonicalBytes.writeUuid(output, entry.getKey());
            writeEntity(output, entry.getValue().before());
            writeEntity(output, entry.getValue().after());
        }
        if (record.payload().biomes().size() > MAX_ENTRIES) throw new IOException("Too many action biome entries");
        output.writeInt(record.payload().biomes().size());
        for (var entry : record.payload().biomes().entrySet()) {
            output.writeInt(entry.getKey().x()); output.writeInt(entry.getKey().y()); output.writeInt(entry.getKey().z());
            CanonicalBytes.writeString(output, entry.getValue().before(), MAX_STRING, "before biome");
            CanonicalBytes.writeString(output, entry.getValue().after(), MAX_STRING, "after biome");
        }
    }

    public WorldActionRecord decode(WorldActionRecord.Metadata expected, WorldDataVersion version,
            long maximumBytes, DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC || !CanonicalBytes.readUuid(input).equals(expected.id())
                || input.readLong() != expected.revision() || input.readInt() != version.value()) {
            throw new IOException("Action payload identity, revision or data version mismatch");
        }
        var budget = new Budget(maximumBytes);
        try {
            int count = budget.count(input, 64);
            var palette = new ArrayList<String>();
            for (int index = 0; index < count; index++) {
                String state = budget.string(input);
                if (state.isBlank()) throw new IOException("Empty action block state");
                palette.add(state);
            }
            count = budget.count(input, 384);
            Map<BlockPosition, BlockDelta> blocks = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                var position = new BlockPosition(input.readInt(), input.readInt(), input.readInt());
                var delta = new BlockDelta(readBlock(input, palette, budget), readBlock(input, palette, budget));
                if (blocks.put(position, delta) != null) throw new IOException("Duplicate action block position");
            }
            count = budget.count(input, 512);
            Map<UUID, EntityDelta> entities = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                UUID id = CanonicalBytes.readUuid(input);
                var delta = new EntityDelta(readEntity(input, id, budget), readEntity(input, id, budget));
                if (entities.put(id, delta) != null) throw new IOException("Duplicate action entity UUID");
            }
            count = budget.count(input, 256);
            var biomes = new LinkedHashMap<BiomePosition, BiomeDelta>();
            for (int index = 0; index < count; index++) {
                var position = new BiomePosition(input.readInt(), input.readInt(), input.readInt());
                if (biomes.put(position, new BiomeDelta(budget.string(input), budget.string(input))) != null) {
                    throw new IOException("Duplicate action biome position");
                }
            }
            if (input.read() != -1) throw new IOException("Trailing action payload bytes");
            return new WorldActionRecord(expected, new WorldActionRecord.Payload(blocks, entities, biomes));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid action payload", invalid);
        }
    }

    private void writeBlock(DataOutputStream output, BlockSnapshot block, Map<String, Integer> palette) throws IOException {
        output.writeInt(palette.get(block.blockState()));
        writeNbt(output, block.blockEntity());
    }

    private BlockSnapshot readBlock(DataInputStream input, List<String> palette, Budget budget) throws IOException {
        int state = input.readInt();
        if (state < 0 || state >= palette.size()) throw new IOException("Invalid action block palette index");
        return new BlockSnapshot(palette.get(state), readNbt(input, budget));
    }

    private void writeEntity(DataOutputStream output, Optional<EntityState> entity) throws IOException {
        output.writeBoolean(entity.isPresent());
        if (entity.isPresent()) {
            var value = entity.orElseThrow();
            CanonicalBytes.writeString(output, value.type(), MAX_STRING, "entity type");
            CanonicalBytes.write(output, value.nbt().bytes(), MAX_NBT, "entity NBT");
        }
    }

    private Optional<EntityState> readEntity(DataInputStream input, UUID id, Budget budget) throws IOException {
        return present(input) ? Optional.of(new EntityState(id, budget.string(input), budget.nbt(input))) : Optional.empty();
    }

    private void writeNbt(DataOutputStream output, Optional<CanonicalNbt> nbt) throws IOException {
        output.writeBoolean(nbt.isPresent());
        if (nbt.isPresent()) CanonicalBytes.write(output, nbt.orElseThrow().bytes(), MAX_NBT, "block entity NBT");
    }

    private Optional<CanonicalNbt> readNbt(DataInputStream input, Budget budget) throws IOException {
        return present(input) ? Optional.of(budget.nbt(input)) : Optional.empty();
    }

    private static boolean present(DataInputStream input) throws IOException {
        int value = input.readUnsignedByte();
        if (value > 1) throw new IOException("Invalid action endpoint flag");
        return value == 1;
    }

    private static final class Budget {
        private long remaining;
        private Budget(long bytes) {
            if (bytes < 1) throw new IllegalArgumentException("Invalid action decode budget");
            remaining = bytes;
        }

        private int count(DataInputStream input, int entryBytes) throws IOException {
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES || (long) count * entryBytes > remaining) {
                throw new IOException("Action payload exceeds its decode budget");
            }
            remaining -= (long) count * entryBytes;
            return count;
        }

        private byte[] bytes(DataInputStream input, int maximum, String label) throws IOException {
            byte[] value = CanonicalBytes.read(input, (int) Math.min(maximum, remaining / 2), label);
            remaining -= 2L * value.length;
            return value;
        }

        private String string(DataInputStream input) throws IOException {
            return new String(bytes(input, MAX_STRING, "action string"), StandardCharsets.UTF_8);
        }

        private CanonicalNbt nbt(DataInputStream input) throws IOException {
            return new CanonicalNbt(bytes(input, MAX_NBT, "action NBT"));
        }
    }
}
