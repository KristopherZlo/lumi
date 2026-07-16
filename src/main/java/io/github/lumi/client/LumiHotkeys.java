package io.github.lumi.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.LumiMod;
import java.util.List;
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
    private final KeyMapping info = mapping("key.lumi.hotkey_info", InputConstants.KEY_I);
    private final KeyMapping[] branches = {
            mapping("key.lumi.branch_slot.1", InputConstants.KEY_1),
            mapping("key.lumi.branch_slot.2", InputConstants.KEY_2),
            mapping("key.lumi.branch_slot.3", InputConstants.KEY_3),
            mapping("key.lumi.branch_slot.4", InputConstants.KEY_4),
            mapping("key.lumi.branch_slot.5", InputConstants.KEY_5),
            mapping("key.lumi.branch_slot.6", InputConstants.KEY_6),
            mapping("key.lumi.branch_slot.7", InputConstants.KEY_7),
            mapping("key.lumi.branch_slot.8", InputConstants.KEY_8),
            mapping("key.lumi.branch_slot.9", InputConstants.KEY_9),
            mapping("key.lumi.branch_slot.0", InputConstants.KEY_0)
    };

    public LumiHotkeys(HotkeyActionDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public void register() {
        KeyBindingHelper.registerKeyBinding(dashboard);
        KeyBindingHelper.registerKeyBinding(save);
        KeyBindingHelper.registerKeyBinding(undo);
        KeyBindingHelper.registerKeyBinding(redo);
        KeyBindingHelper.registerKeyBinding(rollback);
        KeyBindingHelper.registerKeyBinding(info);
        for (KeyMapping branch : branches) {
            KeyBindingHelper.registerKeyBinding(branch);
        }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        boolean canAct = client.player != null && client.screen == null && altDown(client);
        consume(dashboard, canAct, HotkeyActionDispatcher.Action.DASHBOARD);
        consume(save, canAct, HotkeyActionDispatcher.Action.SAVE);
        consume(undo, canAct, HotkeyActionDispatcher.Action.UNDO);
        consume(redo, canAct, HotkeyActionDispatcher.Action.REDO);
        consume(rollback, canAct, HotkeyActionDispatcher.Action.QUICK_ROLLBACK);
        consume(info, canAct, HotkeyActionDispatcher.Action.HOTKEYS);
        for (int slot = 0; slot < branches.length; slot++) {
            if (consume(branches[slot]) && canAct) {
                dispatcher.switchBranch(slot);
            }
        }
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

    private static boolean consume(KeyMapping mapping) {
        boolean clicked = false;
        while (mapping.consumeClick()) {
            clicked = true;
        }
        return clicked;
    }

    private static boolean altDown(Minecraft client) {
        return InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_RALT);
    }

    private static KeyMapping mapping(String translationKey, int key) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    public static List<Shortcut> shortcuts(KeyMapping[] mappings) {
        return List.of(
                shortcut("luma.hotkeys.open_workspace", "luma.hotkeys.open_workspace_help",
                        mapping(mappings, "key.lumi.open_dashboard")),
                shortcut("luma.hotkeys.quick_save", "luma.hotkeys.quick_save_help",
                        mapping(mappings, "key.lumi.quick_save")),
                shortcut("luma.hotkeys.undo", "luma.hotkeys.undo_help",
                        mapping(mappings, "key.lumi.undo")),
                shortcut("luma.hotkeys.redo", "luma.hotkeys.redo_help",
                        mapping(mappings, "key.lumi.redo")),
                shortcut("luma.hotkeys.quick_rollback",
                        "luma.hotkeys.quick_rollback_help",
                        mapping(mappings, "key.lumi.quick_rollback")),
                shortcut("luma.hotkeys.shortcut_info",
                        "luma.hotkeys.shortcut_info_help",
                        mapping(mappings, "key.lumi.hotkey_info")));
    }

    private static KeyMapping mapping(KeyMapping[] mappings, String name) {
        for (KeyMapping mapping : mappings) {
            if (name.equals(mapping.getName())) {
                return mapping;
            }
        }
        throw new IllegalStateException("Missing Lumi key mapping: " + name);
    }

    private static Shortcut shortcut(String labelKey, String helpKey, KeyMapping key) {
        return new Shortcut(labelKey, helpKey, key.getTranslatedKeyMessage().getString());
    }

    public record Shortcut(String labelKey, String helpKey, String key) { }
}
