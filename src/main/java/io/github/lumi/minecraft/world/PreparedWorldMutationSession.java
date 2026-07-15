package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Applies and verifies prepared sections one mutation at a time. */
public final class PreparedWorldMutationSession implements WorldStateApply.ApplySession {
    private final PreparedMinecraftState target;
    private final PreparedWorldAccess world;
    private final LongSupplier nanoTime;
    private MutationCursor apply;
    private MutationCursor repair;
    private int verificationIndex;

    public PreparedWorldMutationSession(
            PreparedMinecraftState target, PreparedWorldAccess world, LongSupplier nanoTime) {
        this.target = Objects.requireNonNull(target, "target");
        this.world = Objects.requireNonNull(world, "world");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (!target.entities().isEmpty()) {
            throw new IllegalArgumentException("Entity apply is not attached to this cursor yet");
        }
        apply = new MutationCursor();
    }

    @Override
    public boolean applyUntil(long deadlineNanos) throws IOException {
        return apply.advance(deadlineNanos);
    }

    @Override
    public WorldStateApply.Verification verifyUntil(long deadlineNanos) throws IOException {
        var sections = target.source().sections().entrySet().stream().toList();
        while (verificationIndex < sections.size() && nanoTime.getAsLong() < deadlineNanos) {
            var expected = sections.get(verificationIndex++);
            if (!expected.getValue().equals(world.captureSection(expected.getKey()))) {
                return WorldStateApply.Verification.MISMATCH;
            }
        }
        return verificationIndex == sections.size()
                ? WorldStateApply.Verification.VERIFIED
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
        verificationIndex = 0;
    }

    private final class MutationCursor {
        private final List<Map.Entry<SectionKey, DecodedSection>> sections =
                new ArrayList<>(target.sections().entrySet());
        private int sectionIndex;
        private int blockIndex;
        private List<Integer> removals = List.of();
        private int removalIndex;
        private List<Map.Entry<Integer, net.minecraft.nbt.CompoundTag>> blockEntities = List.of();
        private int blockEntityIndex;
        private Phase phase = Phase.BLOCKS;

        private boolean advance(long deadlineNanos) throws IOException {
            while (phase != Phase.COMPLETE && nanoTime.getAsLong() < deadlineNanos) {
                step();
            }
            return phase == Phase.COMPLETE;
        }

        private void step() throws IOException {
            if (sectionIndex == sections.size()) {
                phase = Phase.COMPLETE;
                return;
            }
            var section = sections.get(sectionIndex);
            if (phase == Phase.BLOCKS) {
                world.setBlock(section.getKey(), blockIndex,
                        section.getValue().blockStates().get(blockIndex++));
                if (blockIndex == io.github.lumi.domain.model.SectionBlob.BLOCK_COUNT) {
                    removals = world.blockEntityIndexes(section.getKey()).stream()
                            .filter(index -> !section.getValue().blockEntities().containsKey(index))
                            .toList();
                    phase = Phase.REMOVE_BLOCK_ENTITIES;
                }
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
                blockIndex = 0;
                removals = List.of();
                removalIndex = 0;
                blockEntities = List.of();
                blockEntityIndex = 0;
                phase = Phase.BLOCKS;
            }
        }
    }

    private enum Phase {
        BLOCKS,
        REMOVE_BLOCK_ENTITIES,
        LOAD_BLOCK_ENTITIES,
        COMPLETE
    }
}
