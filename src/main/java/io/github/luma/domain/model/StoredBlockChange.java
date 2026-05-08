package io.github.luma.domain.model;

import java.util.Objects;

public record StoredBlockChange(
        BlockPoint pos,
        StatePayload oldValue,
        StatePayload newValue,
        boolean hidden
) {

    public StoredBlockChange(BlockPoint pos, StatePayload oldValue, StatePayload newValue) {
        this(pos, oldValue, newValue, false);
    }

    public StoredBlockChange withLatestState(StatePayload newValue) {
        return new StoredBlockChange(this.pos, this.oldValue, newValue, this.hidden);
    }

    public StoredBlockChange withLatestChange(StoredBlockChange change) {
        if (change == null) {
            return this;
        }
        return new StoredBlockChange(
                this.pos,
                this.oldValue,
                change.newValue(),
                this.hidden && change.hidden()
        );
    }

    public StoredBlockChange inverse() {
        return new StoredBlockChange(this.pos, this.newValue, this.oldValue, this.hidden);
    }

    public StoredBlockChange asHidden() {
        return this.hidden ? this : new StoredBlockChange(this.pos, this.oldValue, this.newValue, true);
    }

    public boolean visibleInBuilderSurfaces() {
        return !this.hidden;
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
