package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.UUID;

/** Applies and verifies prepared world objects one section or entity mutation at a time. */
public final class PreparedWorldMutationSession implements WorldStateApply.ApplySession {
    private final PreparedMinecraftState target;
    private final PreparedWorldAccess world;
    private final LongSupplier nanoTime;
    private final ChunkLoadSession chunks;
    private MutationCursor apply;
    private MutationCursor repair;
    private int sectionVerificationIndex;
    private int entityVerificationIndex;
    private boolean playerSpawnsVerified;
    private boolean chunksRetained;

    public PreparedWorldMutationSession(
            PreparedMinecraftState target, PreparedWorldAccess world, LongSupplier nanoTime) {
        this(target, world, nanoTime, null);
    }

    public PreparedWorldMutationSession(
            PreparedMinecraftState target,
            PreparedWorldAccess world,
            LongSupplier nanoTime,
            ChunkLoadSession chunks) {
        this.target = Objects.requireNonNull(target, "target");
        this.world = Objects.requireNonNull(world, "world");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.chunks = chunks;
        apply = new MutationCursor();
    }

    @Override
    public boolean applyUntil(long deadlineNanos) throws IOException {
        if (chunks != null && !prepareChunksUntil(deadlineNanos)) {
            return false;
        }
        return apply.advance(deadlineNanos);
    }

    private boolean prepareChunksUntil(long deadlineNanos) throws IOException {
        if (!chunksRetained) {
            var keys = new ArrayList<HistoryKey>();
            keys.addAll(target.source().sections().keySet());
            keys.addAll(target.source().entities().keySet());
            chunks.retain(keys);
            chunksRetained = true;
        }
        return chunks.loadUntil(deadlineNanos);
    }

    @Override
    public WorldStateApply.Verification verifyUntil(long deadlineNanos) throws IOException {
        var sections = target.source().sections().entrySet().stream().toList();
        while (sectionVerificationIndex < sections.size()
                && nanoTime.getAsLong() < deadlineNanos) {
            var expected = sections.get(sectionVerificationIndex++);
            if (!expected.getValue().equals(world.captureSection(expected.getKey()))) {
                return WorldStateApply.Verification.MISMATCH;
            }
        }
        var entities = target.source().entities().entrySet().stream().toList();
        while (sectionVerificationIndex == sections.size()
                && entityVerificationIndex < entities.size()
                && nanoTime.getAsLong() < deadlineNanos) {
            var expected = entities.get(entityVerificationIndex++);
            if (!expected.getValue().equals(world.captureEntities(expected.getKey()))) {
                return WorldStateApply.Verification.MISMATCH;
            }
        }
        if (sectionVerificationIndex == sections.size()
                && entityVerificationIndex == entities.size()
                && !playerSpawnsVerified
                && nanoTime.getAsLong() < deadlineNanos) {
            if (!world.matchesPlayerSpawns(target.source().playerSpawns())) {
                return WorldStateApply.Verification.MISMATCH;
            }
            playerSpawnsVerified = true;
        }
        return playerSpawnsVerified ? WorldStateApply.Verification.VERIFIED
                : WorldStateApply.Verification.IN_PROGRESS;
    }

    @Override
    public boolean repairUntil(long deadlineNanos) throws IOException {
        if (repair == null) {
            repair = new MutationCursor();
        }
        return repair.advance(deadlineNanos);
    }

    @Override
    public void restartVerification() {
        sectionVerificationIndex = 0;
        entityVerificationIndex = 0;
        playerSpawnsVerified = false;
    }

    @Override
    public void close() {
        if (chunks != null) {
            chunks.close();
        }
    }

    private final class MutationCursor {
        private final List<Map.Entry<SectionKey, DecodedSection>> sections =
                new ArrayList<>(target.sections().entrySet());
        private final List<Map.Entry<EntityChunkKey, DecodedEntityChunk>> entities =
                new ArrayList<>(target.entities().entrySet());
        private int sectionIndex;
        private List<Integer> removals = List.of();
        private int removalIndex;
        private List<Map.Entry<Integer, net.minecraft.nbt.CompoundTag>> blockEntities = List.of();
        private int blockEntityIndex;
        private int entityIndex;
        private List<UUID> entityRemovals = List.of();
        private int entityRemovalIndex;
        private int entityAddIndex;
        private boolean playerSpawnsApplied;
        private Phase phase = Phase.BLOCKS;

        private boolean advance(long deadlineNanos) throws IOException {
            while (phase != Phase.COMPLETE && nanoTime.getAsLong() < deadlineNanos) {
                step();
            }
            return phase == Phase.COMPLETE;
        }

        private void step() throws IOException {
            if (sectionIndex < sections.size()) {
                stepSection();
            } else if (entityIndex < entities.size()) {
                stepEntityChunk();
            } else if (!playerSpawnsApplied) {
                world.applyPlayerSpawns(target.source().playerSpawns());
                playerSpawnsApplied = true;
            } else {
                phase = Phase.COMPLETE;
            }
        }

        private void stepSection() throws IOException {
            var section = sections.get(sectionIndex);
            if (phase == Phase.BLOCKS) {
                world.applySection(section.getKey(), section.getValue());
                removals = world.blockEntityIndexes(section.getKey()).stream()
                        .filter(index -> !section.getValue().blockEntities().containsKey(index))
                        .toList();
                phase = Phase.REMOVE_BLOCK_ENTITIES;
            } else if (phase == Phase.REMOVE_BLOCK_ENTITIES) {
                if (removalIndex < removals.size()) {
                    world.removeBlockEntity(section.getKey(), removals.get(removalIndex++));
                } else {
                    blockEntities = new ArrayList<>(
                            section.getValue().blockEntities().entrySet());
                    phase = Phase.LOAD_BLOCK_ENTITIES;
                }
            } else if (blockEntityIndex < blockEntities.size()) {
                var blockEntity = blockEntities.get(blockEntityIndex++);
                world.loadBlockEntity(
                        section.getKey(), blockEntity.getKey(), blockEntity.getValue());
            } else {
                sectionIndex++;
                removals = List.of();
                removalIndex = 0;
                blockEntities = List.of();
                blockEntityIndex = 0;
                phase = Phase.BLOCKS;
            }
        }

        private void stepEntityChunk() throws IOException {
            var entityChunk = entities.get(entityIndex);
            if (phase != Phase.REMOVE_ENTITIES && phase != Phase.ADD_ENTITIES) {
                entityRemovals = world.durableEntityIds(entityChunk.getKey());
                phase = Phase.REMOVE_ENTITIES;
            } else if (phase == Phase.REMOVE_ENTITIES
                    && entityRemovalIndex < entityRemovals.size()) {
                world.removeEntity(
                        entityChunk.getKey(), entityRemovals.get(entityRemovalIndex++));
            } else if (phase == Phase.REMOVE_ENTITIES) {
                phase = Phase.ADD_ENTITIES;
            } else if (entityAddIndex < entityChunk.getValue().entities().size()) {
                world.addEntity(entityChunk.getKey(),
                        entityChunk.getValue().entities().get(entityAddIndex++));
            } else {
                entityIndex++;
                entityRemovals = List.of();
                entityRemovalIndex = 0;
                entityAddIndex = 0;
                phase = Phase.BLOCKS;
            }
        }
    }

    private enum Phase {
        BLOCKS,
        REMOVE_BLOCK_ENTITIES,
        LOAD_BLOCK_ENTITIES,
        REMOVE_ENTITIES,
        ADD_ENTITIES,
        COMPLETE
    }
}
