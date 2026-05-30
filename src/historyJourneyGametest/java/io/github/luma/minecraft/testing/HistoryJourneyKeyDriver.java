package io.github.luma.minecraft.testing;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.client.input.LumiClientKeyBindings;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;

@SuppressWarnings("UnstableApiUsage")
final class HistoryJourneyKeyDriver {

    void pressUndo(ClientGameTestContext context) throws Exception {
        this.pressChord(context, LumiClientKeyBindings.Role.UNDO);
    }

    void pressRedo(ClientGameTestContext context) throws Exception {
        this.pressChord(context, LumiClientKeyBindings.Role.REDO);
    }

    private void pressChord(ClientGameTestContext context, LumiClientKeyBindings.Role role) throws Exception {
        context.runOnClient(client -> {
            KeyMapping action = this.requiredKey(LumiClientKeyBindings.Role.ACTION);
            KeyMapping target = this.requiredKey(role);
            action.setDown(true);
            target.setDown(true);
            KeyMapping.click(InputConstants.getKey(target.saveString()));
        });
        context.waitTick();
        context.runOnClient(client -> {
            KeyMapping action = this.requiredKey(LumiClientKeyBindings.Role.ACTION);
            KeyMapping target = this.requiredKey(role);
            target.setDown(false);
            action.setDown(false);
        });
        context.waitTick();
    }

    private KeyMapping requiredKey(LumiClientKeyBindings.Role role) {
        KeyMapping key = LumiClientKeyBindings.key(role);
        if (key == null) {
            throw new IllegalStateException("Lumi key binding is not configured: " + role);
        }
        return key;
    }
}
