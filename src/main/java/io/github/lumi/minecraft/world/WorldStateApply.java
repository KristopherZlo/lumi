package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.service.RestorePlanMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.io.IOException;
import java.util.function.LongConsumer;

/** Server-thread port for bounded application of already decoded world state. */
public interface WorldStateApply {
    /**
     * Called off-thread. Implementations must validate every persistent
     * representation here; bounded native decoding may continue off-thread
     * after {@link #begin(PreparedState)}, but never on the apply thread.
     */
    PreparedState prepare(State target) throws IOException;

    default PreparedState prepare(State target, LongConsumer progress) throws IOException {
        PreparedState prepared = prepare(target);
        progress.accept((long) target.sections().size() + target.entities().size());
        return prepared;
    }

    default PreparedState prepare(
            State target, State base, LongConsumer progress) throws IOException {
        return prepare(target, progress);
    }

    default PreparedStates prepareBoth(
            State target,
            State returnPoint,
            LongConsumer targetProgress,
            LongConsumer returnProgress) throws IOException {
        targetProgress.accept(0);
        PreparedState preparedTarget = prepare(target, returnPoint, targetProgress);
        returnProgress.accept(0);
        PreparedState preparedReturn = prepare(returnPoint, target, returnProgress);
        return new PreparedStates(preparedTarget, preparedReturn);
    }

    default PreparedState replacePreparedSource(
            PreparedState prepared, State source) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        return prepare(Objects.requireNonNull(source, "source"));
    }

    default PreparedStates composePrepared(
            PreparedStates following,
            PreparedStates preceding,
            State target,
            State returnPoint) throws IOException {
        Objects.requireNonNull(following, "following");
        Objects.requireNonNull(preceding, "preceding");
        return prepareBoth(target, returnPoint, ignored -> { }, ignored -> { });
    }

    /** Creates cursors only. World mutation starts with {@link ApplySession#applyUntil(long)}. */
    ApplySession begin(PreparedState target);

    /** Stops stale preparation while preserving only reusable, non-world state. */
    default PrewarmHandoff suspendPrewarm(ApplySession session) {
        Objects.requireNonNull(session, "session").close();
        return PrewarmHandoff.NONE;
    }

    /** Creates a session and consumes an optional prewarm handoff. */
    default ApplySession begin(PreparedState target, PrewarmHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff").close();
        return begin(target);
    }

    interface PreparedState {
        State source();
    }

    interface PrewarmHandoff extends AutoCloseable {
        PrewarmHandoff NONE = () -> { };

        @Override
        void close();
    }

    record PreparedStates(PreparedState target, PreparedState returnPoint) {
        public PreparedStates {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(returnPoint, "returnPoint");
        }
    }

    record State(
            Map<SectionKey, SectionBlob> sections,
            Map<EntityChunkKey, EntityChunkBlob> entities,
            Map<UUID, PlayerSpawn> playerSpawns,
            boolean playerSpawnsIncluded) {
        public State {
            sections = immutable(Objects.requireNonNull(sections, "sections"));
            entities = immutable(Objects.requireNonNull(entities, "entities"));
            playerSpawns = Map.copyOf(Objects.requireNonNull(playerSpawns, "playerSpawns"));
        }

        public State(
                Map<SectionKey, SectionBlob> sections,
                Map<EntityChunkKey, EntityChunkBlob> entities) {
            this(sections, entities, Map.of(), false);
        }

        public State(
                Map<SectionKey, SectionBlob> sections,
                Map<EntityChunkKey, EntityChunkBlob> entities,
                Map<UUID, PlayerSpawn> playerSpawns) {
            this(sections, entities, playerSpawns, true);
        }

        private static <K, V> Map<K, V> immutable(Map<K, V> values) {
            return values instanceof RestorePlanMap<?, ?> ? values : Map.copyOf(values);
        }
    }

    interface ApplySession extends AutoCloseable {
        /** Advances non-mutating readiness work before the operation owns the world. */
        default boolean prewarmUntil(long deadlineNanos) throws IOException {
            return true;
        }

        boolean applyUntil(long deadlineNanos) throws IOException;

        Verification verifyUntil(long deadlineNanos) throws IOException;

        boolean persistUntil(long deadlineNanos) throws IOException;

        boolean repairUntil(long deadlineNanos) throws IOException;

        void restartVerification();

        /** True when successful apply already includes exact verification and persistence. */
        default boolean applyCompletesPersistence() {
            return false;
        }

        default ApplyProgress progress() {
            return new ApplyProgress("apply", 0, 0);
        }

        default RestoreApplyStatistics statistics() {
            return RestoreApplyStatistics.EMPTY;
        }

        @Override
        default void close() { }
    }

    record ApplyProgress(String phase, long completed, long total) {
        public ApplyProgress {
            Objects.requireNonNull(phase, "phase");
            if (phase.isBlank() || completed < 0 || total < 0
                    || total > 0 && completed > total) {
                throw new IllegalArgumentException("Invalid world apply progress");
            }
        }
    }

    enum Verification {
        IN_PROGRESS,
        VERIFIED,
        MISMATCH
    }
}
