package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LumiPermissionServiceTest {
    private final MemoryOptIns optIns = new MemoryOptIns();
    private final LumiPermissionService service = new LumiPermissionService(optIns);
    private final UUID player = UUID.randomUUID();

    @Test
    void requiresOperatorBeforeAnyLumiAction() throws IOException {
        assertEquals(PermissionDecision.OPERATOR_REQUIRED,
                service.evaluate(new PermissionSubject(player, false, false)));
    }

    @Test
    void requiresExplicitOptInForOperatorInSurvival() throws IOException {
        assertEquals(PermissionDecision.SURVIVAL_OPT_IN_REQUIRED,
                service.evaluate(new PermissionSubject(player, true, true)));

        service.setSurvivalEnabled(new PermissionSubject(player, true, true), true);

        assertTrue(service.isSurvivalEnabled(player));
        assertEquals(PermissionDecision.ALLOWED,
                service.evaluate(new PermissionSubject(player, true, true)));
        assertTrue(optIns.enabled.contains(player));
    }

    @Test
    void permitsOperatorOutsideSurvivalWithoutOptIn() throws IOException {
        assertEquals(PermissionDecision.ALLOWED,
                service.evaluate(new PermissionSubject(player, true, false)));
        assertFalse(optIns.enabled.contains(player));
    }

    @Test
    void nonOperatorCannotChangeOptIn() {
        assertThrows(SecurityException.class, () -> service.setSurvivalEnabled(
                new PermissionSubject(player, false, true), true));
    }

    private static final class MemoryOptIns implements SurvivalOptInStore {
        private final HashSet<UUID> enabled = new HashSet<>();

        @Override public boolean isEnabled(UUID playerId) {
            return enabled.contains(playerId);
        }

        @Override public void setEnabled(UUID playerId, boolean value) {
            if (value) {
                enabled.add(playerId);
            } else {
                enabled.remove(playerId);
            }
        }
    }
}
