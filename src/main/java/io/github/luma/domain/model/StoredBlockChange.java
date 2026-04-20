package io.github.luma.domain.model;

import java.util.Objects;

public record StoredBlockChange(
        BlockPoint pos,
        StatePayload oldValue,
        StatePayload newValue
) {

    public StoredBlockChange withLatestState(StatePayload newValue) {
        return new StoredBlockChange(this.pos, this.oldValue, newValue);
    }

    public boolean isNoOp() {
        return Objects.equals(this.oldValue, this.newValue)
                || (this.oldValue != null && this.oldValue.equalsState(this.newValue));
    }

    public BlockChangeRecord toRecord() {
        return new BlockChangeRecord(
                this.pos,
                this.oldValue == null ? "" : this.oldValue.toStateSnbt(),
                this.newValue == null ? "" : this.newValue.toStateSnbt(),
                this.oldValue == null ? "" : this.oldValue.toBlockEntitySnbt(),
                this.newValue == null ? "" : this.newValue.toBlockEntitySnbt()
        );
    }
}
