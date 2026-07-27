package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.service.LiveActionJournal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CausalTokenRegistryTest {
    @Test
    void delayedWorkResumesClosedRootAndCanBeCancelledByAction() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID player = new UUID(0, 21);
        UUID action;
        try (var root = DirectLiveActionContext.open(journal, player)) {
            action = DirectLiveActionContext.current(journal).orElseThrow();
            journal.record(action, new BlockPosition(1, 0, 0), block("air"), block("stone"));
        }
        CausalTokenRegistry<String, UUID> tokens = new CausalTokenRegistry<>();
        tokens.remember("redstone-tick", action);

        try (var resumed = DirectLiveActionContext.resume(
                journal, tokens.take("redstone-tick").orElseThrow())) {
            journal.record(action, new BlockPosition(2, 0, 0), block("air"), block("redstone_wire"));
            tokens.remember("piston-event", action);
        }

        assertEquals(2, journal.prepareUndo(player).orElseThrow().expected().size());
        assertEquals(true, tokens.anyKey("piston-event"::equals));
        assertEquals(true, tokens.anyMatch(action::equals));
        assertEquals(Set.of(action), tokens.owners("piston-event"::equals));
        assertEquals(Set.of("piston-event"), tokens.cancel(action::equals));
        assertEquals(false, tokens.anyMatch(action::equals));
    }

    @Test
    void cancelsOnlyMatchingWorkKeysAndReturnsTheirOwners() {
        CausalTokenRegistry<String, UUID> tokens = new CausalTokenRegistry<>();
        UUID inside = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        tokens.remember("inside:redstone", inside);
        tokens.remember("outside:redstone", outside);

        assertEquals(
                Map.of("inside:redstone", inside),
                tokens.cancelKeys(key -> key.startsWith("inside:")));
        assertEquals(false, tokens.anyMatch(inside::equals));
        assertEquals(true, tokens.anyMatch(outside::equals));
    }

    private static BlockSnapshot block(String id) {
        return new BlockSnapshot("minecraft:" + id, Optional.empty());
    }
}
