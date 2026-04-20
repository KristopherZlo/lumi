package io.github.luma.domain.model;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

public record StatePayload(
        CompoundTag stateTag,
        CompoundTag blockEntityTag
) {

    public static StatePayload capture(BlockState state, CompoundTag blockEntityTag) {
        return new StatePayload(
                NbtUtils.writeBlockState(state),
                blockEntityTag == null ? null : blockEntityTag.copy()
        );
    }

    public String blockId() {
        return this.stateTag == null ? "minecraft:air" : this.stateTag.getString("Name").orElse("minecraft:air");
    }

    public String toStateSnbt() {
        return this.stateTag == null ? "" : NbtUtils.structureToSnbt(this.stateTag.copy());
    }

    public String toBlockEntitySnbt() {
        return this.blockEntityTag == null ? "" : NbtUtils.structureToSnbt(this.blockEntityTag.copy());
    }

    public StatePayload withBlockEntity(CompoundTag blockEntityTag) {
        return new StatePayload(
                this.stateTag == null ? null : this.stateTag.copy(),
                blockEntityTag == null ? null : blockEntityTag.copy()
        );
    }

    public boolean equalsState(StatePayload other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(this.stateTag, other.stateTag)
                && Objects.equals(this.blockEntityTag, other.blockEntityTag);
    }
}
