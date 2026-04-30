package io.github.luma.integration.common;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalToolMutationSourceResolverTest {

    @Test
    void letsAxiomOverridePlayerSource() {
        ObservedExternalToolOperation axiom = new ObservedExternalToolOperation(
                WorldMutationSource.AXIOM,
                "axiom",
                "axiom-action"
        );
        ExternalToolMutationSourceResolver resolver = new ExternalToolMutationSourceResolver(() -> Optional.of(axiom));

        Optional<ObservedExternalToolOperation> resolved = resolver.detectPlayerSourceOverride(
                WorldMutationSource.PLAYER,
                false
        );

        assertTrue(resolved.isPresent());
        assertEquals(axiom, resolved.get());
    }

    @Test
    void keepsNonAxiomToolFramesOutOfPlayerBreaks() {
        ExternalToolMutationSourceResolver resolver = new ExternalToolMutationSourceResolver(() -> Optional.of(
                new ObservedExternalToolOperation(WorldMutationSource.WORLDEDIT, "worldedit", "worldedit-action")
        ));

        assertTrue(resolver.detectPlayerSourceOverride(WorldMutationSource.PLAYER, false).isEmpty());
    }

    @Test
    void skipsPlayerOverrideDetectionWhenSourceCannotUseIt() {
        AtomicBoolean inspectedStack = new AtomicBoolean(false);
        ExternalToolMutationSourceResolver resolver = new ExternalToolMutationSourceResolver(() -> {
            inspectedStack.set(true);
            return Optional.of(new ObservedExternalToolOperation(WorldMutationSource.AXIOM, "axiom", "axiom-action"));
        });

        assertTrue(resolver.detectPlayerSourceOverride(WorldMutationSource.SYSTEM, false).isEmpty());
        assertFalse(inspectedStack.get());
    }

    @Test
    void skipsAllDetectionWhenCaptureIsSuppressed() {
        AtomicBoolean inspectedStack = new AtomicBoolean(false);
        ExternalToolMutationSourceResolver resolver = new ExternalToolMutationSourceResolver(() -> {
            inspectedStack.set(true);
            return Optional.of(new ObservedExternalToolOperation(WorldMutationSource.AXIOM, "axiom", "axiom-action"));
        });

        assertTrue(resolver.detectUnattributedOperation(true).isEmpty());
        assertTrue(resolver.detectPlayerSourceOverride(WorldMutationSource.PLAYER, true).isEmpty());
        assertFalse(inspectedStack.get());
    }
}
