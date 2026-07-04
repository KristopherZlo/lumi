package io.github.luma.integration.axiom;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomSetBlockPacketCaptureServiceTest {

    private static final UUID ACTION_UUID = UUID.fromString("00000000-0000-0000-0000-000000000064");

    @Test
    void packetSourceUsesAxiomActionIdentity() {
        AxiomSetBlockPacketCaptureService service = new AxiomSetBlockPacketCaptureService(() -> ACTION_UUID);

        try (WorldMutationContext.SourceFrame ignored = service.pushPacketSource(null)) {
            assertEquals(WorldMutationSource.AXIOM, WorldMutationContext.currentSource());
            assertEquals("axiom", WorldMutationContext.currentActor());
            assertEquals("axiom-set-block-" + ACTION_UUID, WorldMutationContext.currentActionId());
            assertTrue(WorldMutationContext.currentAccessAllowed());
        }

        assertEquals(WorldMutationSource.SYSTEM, WorldMutationContext.currentSource());
    }

    @Test
    void dedicatedServerIdentityRequiresPlayerAccess() {
        AxiomSetBlockPacketCaptureService service = new AxiomSetBlockPacketCaptureService(() -> ACTION_UUID);

        AxiomSetBlockPacketCaptureService.SourceIdentity identity =
                service.sourceIdentity("Builder", true, false);

        assertEquals(WorldMutationSource.AXIOM, identity.source());
        assertEquals("axiom:Builder", identity.actor());
        assertEquals("axiom-set-block-" + ACTION_UUID, identity.actionId());
        assertFalse(identity.accessAllowed());
    }

    @Test
    void integratedServerIdentityAllowsCaptureWithoutPermissionGrant() {
        AxiomSetBlockPacketCaptureService service = new AxiomSetBlockPacketCaptureService(() -> ACTION_UUID);

        AxiomSetBlockPacketCaptureService.SourceIdentity identity =
                service.sourceIdentity("Builder", false, false);

        assertTrue(identity.accessAllowed());
    }

    @Test
    void blankPlayerNameFallsBackToAxiomActor() {
        AxiomSetBlockPacketCaptureService service = new AxiomSetBlockPacketCaptureService(() -> ACTION_UUID);

        AxiomSetBlockPacketCaptureService.SourceIdentity identity =
                service.sourceIdentity(" ", false, true);

        assertEquals("axiom", identity.actor());
    }

}
