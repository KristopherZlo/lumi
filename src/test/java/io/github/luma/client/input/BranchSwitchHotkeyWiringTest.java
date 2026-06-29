package io.github.luma.client.input;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchSwitchHotkeyWiringTest {

    @Test
    void keyboardMixinRoutesAltKeyPressesToBranchSwitchController() throws Exception {
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));
        String mixin = Files.readString(Path.of("src/client/java/io/github/luma/mixin/client/KeyboardHandlerMixin.java"));

        assertTrue(mixins.contains("client.KeyboardHandlerMixin"));
        assertTrue(mixin.contains("int action"));
        assertTrue(mixin.contains("KeyEvent event"));
        assertTrue(mixin.contains("BranchSwitchHotkeyController.getInstance().handleKeyPress(client, action, event)"));
    }

    @Test
    void branchSwitchHotkeysUseTheConfiguredActionKeyInsteadOfHardcodedAlt() throws Exception {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/client/input/BranchSwitchHotkeyController.java"));

        assertTrue(source.contains("LumiClientKeyBindings.Role.ACTION"));
        assertTrue(source.contains("keyBindingState.isDown(client, actionKey)"));
        assertTrue(source.contains("!this.sameKey(event, actionKey)"));
        assertTrue(!source.contains("GLFW_MOD_ALT"));
    }
}
