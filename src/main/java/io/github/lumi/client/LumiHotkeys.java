package io.github.lumi.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.LumiMod;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Registers retained Alt chords and dispatches them only during normal play. */
public final class LumiHotkeys {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "general"));
    private final HotkeyActionDispatcher dispatcher;
    private final KeyMapping dashboard = mapping(
            "key.lumi.open_dashboard", InputConstants.KEY_L);
    private final KeyMapping save = mapping("key.lumi.quick_save", InputConstants.KEY_S);
    private final KeyMapping undo = mapping("key.lumi.undo", InputConstants.KEY_Z);
    private final KeyMapping redo = mapping("key.lumi.redo", InputConstants.KEY_Y);
    private final KeyMapping rollback = mapping(
            "key.lumi.quick_rollback", InputConstants.KEY_R);

    public LumiHotkeys(HotkeyActionDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public void register() {
        KeyBindingHelper.registerKeyBinding(dashboard);
        KeyBindingHelper.registerKeyBinding(save);
        KeyBindingHelper.registerKeyBinding(undo);
        KeyBindingHelper.registerKeyBinding(redo);
        KeyBindingHelper.registerKeyBinding(rollback);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        boolean canAct = client.player != null && client.screen == null && altDown(client);
        consume(dashboard, canAct, HotkeyActionDispatcher.Action.DASHBOARD);
        consume(save, canAct, HotkeyActionDispatcher.Action.SAVE);
        consume(undo, canAct, HotkeyActionDispatcher.Action.UNDO);
        consume(redo, canAct, HotkeyActionDispatcher.Action.REDO);
        consume(rollback, canAct, HotkeyActionDispatcher.Action.QUICK_ROLLBACK);
    }

    private void consume(
            KeyMapping mapping, boolean canAct, HotkeyActionDispatcher.Action action) {
        boolean clicked = false;
        while (mapping.consumeClick()) {
            clicked = true;
        }
        if (clicked && canAct) {
            dispatcher.dispatch(action);
        }
    }

    private static boolean altDown(Minecraft client) {
        return InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_RALT);
    }

    private static KeyMapping mapping(String translationKey, int key) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, CATEGORY);
    }
}
