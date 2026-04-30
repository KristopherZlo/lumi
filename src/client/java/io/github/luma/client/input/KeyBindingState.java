package io.github.luma.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Resolves the physical pressed state for remappable Minecraft key bindings.
 */
public final class KeyBindingState {

    public boolean isDown(Minecraft client, KeyMapping key) {
        if (key == null) {
            return false;
        }
        if (key.isDown()) {
            return true;
        }
        if (client == null || client.getWindow() == null || key.isUnbound()) {
            return false;
        }

        InputConstants.Key boundKey = InputConstants.getKey(key.saveString());
        if (boundKey.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(client.getWindow(), boundKey.getValue());
        }
        if (boundKey.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().handle(), boundKey.getValue()) == GLFW.GLFW_PRESS;
        }
        return false;
    }
}
