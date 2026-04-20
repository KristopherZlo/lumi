package io.github.luma.domain.model;

import java.util.Objects;

public record BlockChangeRecord(
        BlockPoint pos,
        String oldState,
        String newState,
        String oldBlockEntityNbt,
        String newBlockEntityNbt
) {

    public BlockChangeRecord withLatestState(String newState, String newBlockEntityNbt) {
        return new BlockChangeRecord(this.pos, this.oldState, newState, this.oldBlockEntityNbt, newBlockEntityNbt);
    }

    public boolean isNoOp() {
        return Objects.equals(this.oldState, this.newState)
                && Objects.equals(this.oldBlockEntityNbt, this.newBlockEntityNbt);
    }
}
