package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.UUID;

/** Applies and verifies prepared world objects one section or entity mutation at a time. */
public final class PreparedWorldMutationSession implements WorldStateApply.ApplySession {
    private final PreparedMinecraftState target;
    private final PreparedWorldAccess world;
    private final LongSupplier nanoTime;
    private final ChunkLoadSession chunks;
    private final List<SectionKey> sections;
    private final List<EntityChunkKey> entities;
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
        sections = target.sectionKeys();
        entities = target.entityKeys();
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
            chunks.retain(sections);
            chunks.retain(entities);
            chunksRetained = true;
        }
        return chunks.loadUntil(deadlineNanos);
    }

    @Override
    public WorldStateApply.Verification verifyUntil(long deadlineNanos) throws IOException {
        while (sectionVerificationIndex < sections.size()
                && nanoTime.getAsLong() < deadlineNanos) {
            SectionKey key = sections.get(sectionVerificationIndex++);
            if (!target.source().sections().get(key).equals(world.captureSection(key))) {
                return WorldStateApply.Verification.MISMATCH;
            }
        }
        while (sectionVerificationIndex == sections.size()
                && entityVerificationIndex < entities.size()
                && nanoTime.getAsLong() < deadlineNanos) {
            EntityChunkKey key = entities.get(entityVerificationIndex++);
            if (!target.source().entities().get(key).equals(world.captureEntities(key))) {
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
        private int sectionIndex;
        private List<Integer> removals = List.of();
        private int removalIndex;
        private List<java.util.Map.Entry<Integer, net.minecraft.nbt.CompoundTag>> blockEntities =
                List.of();
        private int blockEntityIndex;
        private int entityIndex;
        private List<UUID> entityRemovals = List.of();
        private int entityRemovalIndex;
        private int entityAddIndex;
        private boolean entitiesApplied;
        private boolean playerSpawnsApplied;
        private SectionApplyResult appliedSection;
        private final List<SectionApplyResult> appliedChunkSections = new ArrayList<>();
        private boolean chunkBlockEntitiesChanged;
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
            } else if (!entitiesApplied) {
                stepEntities();
            } else if (!playerSpawnsApplied) {
                world.applyPlayerSpawns(target.source().playerSpawns());
                playerSpawnsApplied = true;
            } else {
                phase = Phase.COMPLETE;
            }
        }

        private void stepSection() throws IOException {
            SectionKey key = sections.get(sectionIndex);
            DecodedSection section = target.sections().get(key);
            if (phase == Phase.BLOCKS) {
                appliedSection = world.applySection(key, section);
                List<Integer> currentBlockEntities = world.blockEntityIndexes(key);
                removals = currentBlockEntities.stream()
                        .filter(index -> !section.blockEntities().containsKey(index))
                        .toList();
                chunkBlockEntitiesChanged |= !currentBlockEntities.isEmpty()
                        || !section.blockEntities().isEmpty();
                phase = Phase.REMOVE_BLOCK_ENTITIES;
            } else if (phase == Phase.REMOVE_BLOCK_ENTITIES) {
                if (removalIndex < removals.size()) {
                    world.removeBlockEntity(key, removals.get(removalIndex++));
                } else {
                    blockEntities = new ArrayList<>(section.blockEntities().entrySet());
                    phase = Phase.LOAD_BLOCK_ENTITIES;
                }
            } else if (blockEntityIndex < blockEntities.size()) {
                var blockEntity = blockEntities.get(blockEntityIndex++);
                world.loadBlockEntity(key, blockEntity.getKey(), blockEntity.getValue());
            } else {
                appliedChunkSections.add(appliedSection);
                sectionIndex++;
                if (sectionIndex == sections.size()
                        || !sameChunk(key, sections.get(sectionIndex))) {
                    world.finishChunk(
                            new ChunkCoordinate(key.chunkX(), key.chunkZ()),
                            List.copyOf(appliedChunkSections),
                            chunkBlockEntitiesChanged);
                    appliedChunkSections.clear();
                    chunkBlockEntitiesChanged = false;
                }
                removals = List.of();
                removalIndex = 0;
                blockEntities = List.of();
                blockEntityIndex = 0;
                phase = Phase.BLOCKS;
            }
        }

        private void stepEntities() throws IOException {
            if (phase == Phase.ADD_ENTITIES) {
                addEntity();
                return;
            }
            removeEntity();
        }

        private void removeEntity() throws IOException {
            if (entityIndex == entities.size()) {
                entityIndex = 0;
                phase = Phase.ADD_ENTITIES;
                return;
            }
            EntityChunkKey key = entities.get(entityIndex);
            if (phase != Phase.REMOVE_ENTITIES) {
                entityRemovals = world.durableEntityIds(key);
                phase = Phase.REMOVE_ENTITIES;
            } else if (phase == Phase.REMOVE_ENTITIES
                    && entityRemovalIndex < entityRemovals.size()) {
                world.removeEntity(key, entityRemovals.get(entityRemovalIndex++));
            } else {
                entityIndex++;
                entityRemovals = List.of();
                entityRemovalIndex = 0;
                phase = Phase.BLOCKS;
            }
        }

        private boolean sameChunk(SectionKey left, SectionKey right) {
            return left.chunkX() == right.chunkX() && left.chunkZ() == right.chunkZ();
        }

        private void addEntity() throws IOException {
            if (entityIndex == entities.size()) {
                entitiesApplied = true;
                return;
            }
            EntityChunkKey key = entities.get(entityIndex);
            DecodedEntityChunk entityChunk = target.entities().get(key);
            if (entityAddIndex < entityChunk.entities().size()) {
                world.addEntity(key, entityChunk.entities().get(entityAddIndex++));
            } else {
                entityIndex++;
                entityAddIndex = 0;
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
