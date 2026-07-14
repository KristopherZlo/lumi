package io.github.luma.minecraft.world;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Bounded mechanism context discovered while persisted changes are decoded off
 * the server tick thread.
 */
public record MechanismReplayScope(
        List<BlockPoint> positions,
        List<ChunkSectionPoint> sections
) {

    public MechanismReplayScope {
        positions = positions == null ? List.of() : List.copyOf(positions);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public static MechanismReplayScope empty() {
        return new MechanismReplayScope(List.of(), List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return this.positions.isEmpty() && this.sections.isEmpty();
    }

    public static final class Builder {

        private final Map<String, BlockPoint> positions = new LinkedHashMap<>();
        private final Map<String, ChunkSectionPoint> sections = new LinkedHashMap<>();

        public Builder addMechanismPosition(BlockPos pos) {
            if (pos == null) {
                return this;
            }
            this.addContextPosition(pos);
            return this;
        }

        public Builder addMechanismSection(BlockPos pos) {
            if (pos == null) {
                return this;
            }
            ChunkSectionPoint section = ChunkSectionPoint.from(BlockPoint.from(pos));
            this.sections.putIfAbsent(sectionKey(section), section);
            return this;
        }

        public Builder addContextPosition(BlockPos pos) {
            return this.addContextPosition(pos == null ? null : BlockPoint.from(pos));
        }

        public Builder addContextPosition(BlockPoint pos) {
            if (pos == null) {
                return this;
            }
            this.positions.putIfAbsent(positionKey(pos), pos);
            return this;
        }

        public Builder addSignalHalo(BlockPos pos) {
            if (pos == null) {
                return this;
            }
            this.addContextPosition(pos);
            for (Direction direction : Direction.values()) {
                this.addContextPosition(pos.relative(direction));
            }
            return this;
        }

        public Builder addAll(MechanismReplayScope scope) {
            if (scope == null) {
                return this;
            }
            for (BlockPoint position : scope.positions()) {
                this.addContextPosition(position);
            }
            for (ChunkSectionPoint section : scope.sections()) {
                this.sections.putIfAbsent(sectionKey(section), section);
            }
            return this;
        }

        public MechanismReplayScope build() {
            return new MechanismReplayScope(
                    List.copyOf(this.positions.values()),
                    List.copyOf(this.sections.values())
            );
        }

        private static String positionKey(BlockPoint pos) {
            return pos.x() + ":" + pos.y() + ":" + pos.z();
        }

        private static String sectionKey(ChunkSectionPoint section) {
            return section.chunkX() + ":" + section.sectionY() + ":" + section.chunkZ();
        }
    }
}
