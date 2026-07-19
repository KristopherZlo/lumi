package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiSelectionToolTest {
    @Test
    void rawAdapterOwnsLegacyClicksWheelAndEitherHand() throws Exception {
        String tool = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiSelectionTool.java"));
        String mixin = Files.readString(Path.of(
                "src/main/java/io/github/lumi/mixin/client/MouseHandlerMixin.java"));

        assertTrue(tool.contains("getMainHandItem().is(Items.WOODEN_SWORD)"));
        assertTrue(tool.contains("getOffhandItem().is(Items.WOODEN_SWORD)"));
        assertTrue(tool.contains("selection.toggleMode()"));
        assertTrue(tool.contains("selection.clear()"));
        assertTrue(tool.contains("SelectionResizeSideResolver.resolve("));
        assertTrue(tool.contains("client.screen == null"));
        assertTrue(mixin.contains("callback.cancel()"));
        assertTrue(mixin.contains("LumiSelectionTool.handleScroll"));
    }
}
