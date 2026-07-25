package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DirectActionMixinScopeTest {
    @Test
    void playerMutationScopesUseExceptionSafeWholeMethodWrappers() throws Exception {
        String source = source("ServerPlayerGameModeMixin.java");

        assertTrue(source.contains("@WrapMethod(method = \"destroyBlock\")"));
        assertTrue(source.contains("@WrapMethod(method = \"useItem\")"));
        assertTrue(source.contains("@WrapMethod(method = \"useItemOn\")"));
        assertTrue(source.contains("try (var ignored = DirectLiveActionContext.open("));
        assertFalse(source.contains("@Inject(method ="));
        assertFalse(source.contains("Deque<"));
    }

    @Test
    void entityInteractionAlwaysFinishesCaptureAndScope() throws Exception {
        String source = source("ServerGamePacketListenerImplMixin.java");

        assertTrue(source.contains("@WrapMethod(method = \"handleInteract\")"));
        assertTrue(source.contains("try (var ignored = DirectLiveActionContext.open("));
        assertTrue(source.contains("finally {"));
        assertTrue(source.contains("lumi$finishCapture("));
        assertFalse(source.contains("@Inject(method ="));
        assertFalse(source.contains("Deque<"));
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/io/github/lumi/mixin", name));
    }
}
