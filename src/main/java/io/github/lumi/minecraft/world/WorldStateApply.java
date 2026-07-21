package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.PlayerSpawn;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.io.IOException;
import java.util.function.LongConsumer;

/** Server-thread port for bounded application of already decoded world state. */
public interface WorldStateApply {
    /** Called off-thread; implementations must decode all persistent representations here. */
    PreparedState prepare(State target) throws IOException;

    default PreparedState prepare(State target, LongConsumer progress) throws IOException {
        PreparedState prepared = prepare(target);
        progress.accept((long) target.sections().size() + target.entities().size());
        return prepared;
    }

    /** Creates cursors only. World mutation starts with {@link ApplySession#applyUntil(long)}. */
    ApplySession begin(PreparedState target);

    interface PreparedState {
        State source();
    }

    record State(
            Map<SectionKey, SectionBlob> sections,
            Map<EntityChunkKey, EntityChunkBlob> entities,
            Map<UUID, PlayerSpawn> playerSpawns) {
        public State {
            sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
            entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
            playerSpawns = Map.copyOf(Objects.requireNonNull(playerSpawns, "playerSpawns"));
        }

        public State(
                Map<SectionKey, SectionBlob> sections,
                Map<EntityChunkKey, EntityChunkBlob> entities) {
            this(sections, entities, Map.of());
        }
    }

    interface ApplySession extends AutoCloseable {
        boolean applyUntil(long deadlineNanos) throws IOException;

        Verification verifyUntil(long deadlineNanos) throws IOException;

        boolean repairUntil(long deadlineNanos) throws IOException;

        void restartVerification();

        @Override
        default void close() { }
    }

    enum Verification {
        IN_PROGRESS,
        VERIFIED,
        MISMATCH
    }
}
