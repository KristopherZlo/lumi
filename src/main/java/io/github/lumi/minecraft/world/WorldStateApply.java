package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.util.Map;
import java.util.Objects;
import java.io.IOException;

/** Server-thread port for bounded application of already decoded world state. */
public interface WorldStateApply {
    /** Called off-thread; implementations must decode all persistent representations here. */
    PreparedState prepare(State target) throws IOException;

    /** Creates cursors only. World mutation starts with {@link ApplySession#applyUntil(long)}. */
    ApplySession begin(PreparedState target);

    interface PreparedState { }

    record State(
            Map<SectionKey, SectionBlob> sections,
            Map<EntityChunkKey, EntityChunkBlob> entities) {
        public State {
            sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
            entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        }
    }

    interface ApplySession {
        boolean applyUntil(long deadlineNanos) throws IOException;

        Verification verifyUntil(long deadlineNanos) throws IOException;

        boolean repairUntil(long deadlineNanos) throws IOException;

        void restartVerification();
    }

    enum Verification {
        IN_PROGRESS,
        VERIFIED,
        MISMATCH
    }
}
