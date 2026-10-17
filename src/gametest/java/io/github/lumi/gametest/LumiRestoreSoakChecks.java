package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** Native endpoint oracle shared by the GameTest and ordinary-client soak. */
final class LumiRestoreSoakChecks {
    private final LumiColdRestoreManifest manifest;

    LumiRestoreSoakChecks(LumiColdRestoreManifest manifest) { this.manifest = manifest; }

    CommitId target(boolean initial) { return initial ? manifest.initialCommit() : manifest.latestCommit(); }

    BranchRef verify(ServerLevel level, boolean initial) throws IOException {
        var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
        var ref = runtime.activeRef();
        if (!ref.commit().equals(target(initial))) throw new AssertionError("Soak published an unexpected HEAD");
        String actual = LumiWorldSnapshot.capture(level, List.of(manifest.area())).sha256();
        String expected = initial ? manifest.initialDigest() : manifest.latestDigest();
        if (!actual.equals(expected)) throw new AssertionError("Soak endpoint digest mismatch: " + actual);
        verifySettled(level);
        return ref;
    }

    static void verifySettled(ServerLevel level) {
        var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
        if (runtime.freeze().isFrozen() || runtime.operations().hasActiveOperation()
                || runtime.recoveryJournal().isPresent()) {
            throw new AssertionError("Completed soak retained a mutation, freeze or journal");
        }
    }
}
