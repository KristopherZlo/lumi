package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.storage.repository.SnapshotWriter;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

/**
 * Rebases direct same-session block events onto the captured session baseline.
 */
final class SessionBaselineStateResolver {

    private static final CompoundTag AIR_STATE = airState();

    StoredBlockChange rebaseToSessionBaseline(CaptureSessionState session, StoredBlockChange change) {
        if (session == null || change == null || change.pos() == null) {
            return change;
        }
        ChunkPoint chunk = ChunkPoint.from(change.pos());
        StatePayload baseline = session.baselineCorrections(List.of(chunk)).get(change.pos());
        if (baseline == null) {
            baseline = this.baselinePayload(session.baselineChunkState(chunk), change.pos());
        }
        if (baseline == null) {
            return change;
        }
        return new StoredBlockChange(change.pos(), baseline, change.newValue(), change.hidden());
    }

    private StatePayload baselinePayload(ChunkSnapshotPayload chunk, BlockPoint pos) {
        if (chunk == null || pos == null || pos.y() < chunk.minBuildHeight() || pos.y() >= chunk.maxBuildHeight()) {
            return null;
        }
        int sectionY = Math.floorDiv(pos.y(), 16);
        ChunkSectionSnapshotPayload section = null;
        for (ChunkSectionSnapshotPayload candidate : chunk.sections()) {
            if (candidate.sectionY() == sectionY) {
                section = candidate;
                break;
            }
        }
        int localX = pos.x() & 15;
        int localY = pos.y() & 15;
        int localZ = pos.z() & 15;
        CompoundTag stateTag = this.readStateTag(section, localX, localY, localZ);
        CompoundTag blockEntityTag = chunk.blockEntities().get(SnapshotWriter.packVerticalIndex(
                pos.y() - chunk.minBuildHeight(),
                localX,
                localZ
        ));
        return new StatePayload(
                stateTag == null ? AIR_STATE.copy() : stateTag.copy(),
                blockEntityTag == null ? null : blockEntityTag.copy()
        );
    }

    private CompoundTag readStateTag(ChunkSectionSnapshotPayload section, int localX, int localY, int localZ) {
        if (section == null || section.palette().isEmpty()) {
            return AIR_STATE;
        }
        int paletteIndex = section.paletteIndexAt(localX, localY, localZ);
        if (paletteIndex < 0 || paletteIndex >= section.palette().size()) {
            return AIR_STATE;
        }
        CompoundTag tag = section.palette().get(paletteIndex);
        return tag == null ? AIR_STATE : tag;
    }

    private static CompoundTag airState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:air");
        return tag;
    }
}
