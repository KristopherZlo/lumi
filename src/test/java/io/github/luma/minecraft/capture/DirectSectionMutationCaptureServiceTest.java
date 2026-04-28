package io.github.luma.minecraft.capture;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DirectSectionMutationCaptureServiceTest {

    @Test
    void skipsExternalToolStackDetectionWhenSectionHasNoServerOwner() {
        AtomicBoolean inspectedStack = new AtomicBoolean(false);
        DirectSectionMutationCaptureService service = new DirectSectionMutationCaptureService(
                () -> {
                    inspectedStack.set(true);
                    return Optional.empty();
                },
                section -> Optional.empty()
        );

        service.captureBefore(null, 0, 0, 0);

        assertFalse(inspectedStack.get());
    }
}
