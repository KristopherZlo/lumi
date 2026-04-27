package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.world.PersistentBlockStatePolicy;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides whether a Minecraft world mutation should become Lumi history.
 */
public final class WorldMutationCapturePolicy {

    private final PersistentBlockStatePolicy blockStatePolicy;

    public WorldMutationCapturePolicy() {
        this(new PersistentBlockStatePolicy());
    }

    WorldMutationCapturePolicy(PersistentBlockStatePolicy blockStatePolicy) {
        this.blockStatePolicy = blockStatePolicy;
    }

    public Optional<CapturedMutation> capture(
            WorldMutationSource source,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CompoundTag oldBlockEntity,
            CompoundTag newBlockEntity
    ) {
        if (pos == null || !this.shouldCaptureMutation(source)) {
            return Optional.empty();
        }
        if (source == WorldMutationSource.PISTON || this.blockStatePolicy.isRuntimeOnlyStateChange(oldState, newState)) {
            return Optional.empty();
        }

        PersistentBlockStatePolicy.PersistentBlockState oldPersistent = this.blockStatePolicy.normalize(oldState, oldBlockEntity);
        PersistentBlockStatePolicy.PersistentBlockState newPersistent = this.blockStatePolicy.normalize(newState, newBlockEntity);
        StoredBlockChange change = new StoredBlockChange(
                BlockPoint.from(pos),
                StatePayload.capture(oldPersistent.state(), oldPersistent.blockEntityTag()),
                StatePayload.capture(newPersistent.state(), newPersistent.blockEntityTag())
        );
        if (change.isNoOp()) {
            return Optional.empty();
        }
        return Optional.of(new CapturedMutation(
                change,
                oldPersistent.state(),
                newPersistent.state(),
                oldPersistent.blockEntityTag(),
                newPersistent.blockEntityTag()
        ));
    }

    public boolean shouldCaptureMutation(WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case PLAYER,
                    ENTITY,
                    EXPLOSION,
                    FLUID,
                    FIRE,
                    GROWTH,
                    BLOCK_UPDATE,
                    FALLING_BLOCK,
                    EXPLOSIVE,
                    MOB,
                    EXTERNAL_TOOL,
                    WORLDEDIT,
                    AXIOM -> true;
            case PISTON, RESTORE, SYSTEM -> false;
        };
    }

    public record CapturedMutation(
            StoredBlockChange change,
            BlockState oldState,
            BlockState newState,
            CompoundTag oldBlockEntity,
            CompoundTag newBlockEntity
    ) {
    }
}
