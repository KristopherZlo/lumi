package io.github.lumi.minecraft.world;

import java.io.IOException;
import java.util.concurrent.Executor;

/** The native editor already applied the target; common verification and recovery still own durability. */
public final class AlreadyAppliedWorldState implements WorldStateApply {
    private final MinecraftWorldStateApply world;
    private final Runnable journaled;
    private final boolean alreadyApplied;
    private boolean first = true;
    public AlreadyAppliedWorldState(MinecraftWorldStateApply world, Runnable journaled, boolean alreadyApplied) {
        this.world = world; this.journaled = journaled; this.alreadyApplied = alreadyApplied;
    }
    @Override public boolean awaitMutationAdmission() throws IOException { return alreadyApplied || world.awaitMutationAdmission(); }
    @Override public Executor persistenceExecutor() { return world.persistenceExecutor(); }
    @Override public PreparedState prepare(State target) throws IOException { return world.prepare(target); }
    @Override public PreparedStates prepareBoth(State target, State before, java.util.function.LongConsumer targetProgress,
            java.util.function.LongConsumer beforeProgress) throws IOException { return WorldStateApply.super.prepareBoth(target, before, targetProgress, beforeProgress); }
    @Override public ApplySession begin(PreparedState state) {
        var session = world.beginLoaded(state);
        if (!first) return session;
        first = false;
        return new ApplySession() {
            @Override public boolean applyUntil(long deadline) throws IOException { journaled.run(); return alreadyApplied || session.applyUntil(deadline); }
            @Override public Verification verifyUntil(long deadline) throws IOException { return session.verifyUntil(deadline); }
            @Override public boolean persistUntil(long deadline) throws IOException { return session.persistUntil(deadline); }
            @Override public boolean repairUntil(long deadline) throws IOException { return session.repairUntil(deadline); }
            @Override public void restartVerification() { session.restartVerification(); }
            @Override public void close() { session.close(); }
        };
    }
}
