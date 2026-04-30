package io.github.luma.ui.screen;

import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class OnboardingScreenTest {

    @Test
    void identifiesEscapeForOnboardingConsumption() {
        Assertions.assertTrue(OnboardingScreen.isEscapeKey(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
        Assertions.assertFalse(OnboardingScreen.isEscapeKey(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0)));
    }
}
