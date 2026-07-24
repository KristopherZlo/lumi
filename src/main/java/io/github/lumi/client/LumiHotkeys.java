package io.github.lumi.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.LumiMod;
import io.github.lumi.client.onboarding.OnboardingEvent;
import java.util.EnumSet;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Registers the standalone dashboard key plus Lumi action chords. */
public final class LumiHotkeys {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "general"));
    private final HotkeyActionDispatcher dispatcher;
    private final Supplier<List<Integer>> branchKeys;
    private final Consumer<OnboardingEvent> onboardingEvents;
    private final Set<Integer> pressedBranchKeys = new HashSet<>();
    private final EnumSet<OnboardingEvent.ShortcutKind> pressedOnboardingKeys =
            EnumSet.noneOf(OnboardingEvent.ShortcutKind.class);
    private final KeyMapping dashboard = mapping(
            "key.lumi.open_dashboard", defaultDashboardKey());
    private final KeyMapping save = mapping("key.lumi.quick_save", InputConstants.KEY_S);
    private final KeyMapping undo = mapping("key.lumi.undo", InputConstants.KEY_Z);
    private final KeyMapping redo = mapping("key.lumi.redo", InputConstants.KEY_Y);
    private final KeyMapping compare = mapping(
            "key.lumi.toggle_compare_overlay", defaultCompareOverlayKey());
    private final KeyMapping actionModifier = mapping(
            "key.lumi.action_modifier", InputConstants.KEY_LALT);
    private final KeyMapping rollback = mapping(
            "key.lumi.quick_rollback", InputConstants.KEY_R);
    private final KeyMapping info = mapping("key.lumi.hotkey_info", InputConstants.KEY_I);
    public LumiHotkeys(HotkeyActionDispatcher dispatcher) {
        this(dispatcher, List::of, ignored -> { });
    }

    public LumiHotkeys(
            HotkeyActionDispatcher dispatcher,
            Supplier<List<Integer>> branchKeys) {
        this(dispatcher, branchKeys, ignored -> { });
    }

    public LumiHotkeys(
            HotkeyActionDispatcher dispatcher,
            Supplier<List<Integer>> branchKeys,
            Consumer<OnboardingEvent> onboardingEvents) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.branchKeys = Objects.requireNonNull(branchKeys, "branchKeys");
        this.onboardingEvents = Objects.requireNonNull(
                onboardingEvents, "onboardingEvents");
    }

    public void register() {
        KeyBindingHelper.registerKeyBinding(dashboard);
        KeyBindingHelper.registerKeyBinding(save);
        KeyBindingHelper.registerKeyBinding(undo);
        KeyBindingHelper.registerKeyBinding(redo);
        KeyBindingHelper.registerKeyBinding(compare);
        KeyBindingHelper.registerKeyBinding(actionModifier);
        KeyBindingHelper.registerKeyBinding(rollback);
        KeyBindingHelper.registerKeyBinding(info);
        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        boolean normalPlay = client.player != null && client.screen == null;
        boolean altDown = actionModifier.isDown();
        publishOnboardingEdges(altDown);
        if (consume(dashboard) && canOpenDashboard(normalPlay)) {
            if (dashboard.same(client.options.keyAdvancements)) {
                consume(client.options.keyAdvancements);
            }
            dispatcher.dispatch(HotkeyActionDispatcher.Action.DASHBOARD);
        }
        consume(compare, normalPlay, HotkeyActionDispatcher.Action.COMPARE_OVERLAY);
        consume(rollback, normalPlay, HotkeyActionDispatcher.Action.QUICK_ROLLBACK);
        boolean canUseChord = normalPlay && altDown;
        consume(save, canUseChord, HotkeyActionDispatcher.Action.SAVE);
        consume(undo, canUseChord, HotkeyActionDispatcher.Action.UNDO);
        consume(redo, canUseChord, HotkeyActionDispatcher.Action.REDO);
        consume(info, canUseChord, HotkeyActionDispatcher.Action.HOTKEYS);
        pollBranchKeys(client, canUseChord);
        consume(actionModifier);
    }

    private void publishOnboardingEdges(boolean modifierDown) {
        publishEdge(OnboardingEvent.ShortcutKind.SAVE,
                modifierDown && save.isDown());
        publishEdge(OnboardingEvent.ShortcutKind.DASHBOARD,
                dashboard.isDown());
    }

    private void publishEdge(
            OnboardingEvent.ShortcutKind shortcut, boolean pressed) {
        boolean changed = pressed
                ? pressedOnboardingKeys.add(shortcut)
                : pressedOnboardingKeys.remove(shortcut);
        if (changed) {
            onboardingEvents.accept(new OnboardingEvent.Shortcut(shortcut, pressed));
        }
    }

    private void pollBranchKeys(Minecraft client, boolean enabled) {
        if (!enabled) {
            pressedBranchKeys.clear();
            return;
        }
        Set<Integer> down = new HashSet<>();
        for (int keyCode : branchKeys.get()) {
            if (InputConstants.isKeyDown(client.getWindow(), keyCode)) {
                down.add(keyCode);
                if (pressedBranchKeys.add(keyCode)) {
                    dispatcher.switchBranch(keyCode);
                }
            }
        }
        pressedBranchKeys.retainAll(down);
    }

    static int defaultDashboardKey() {
        return InputConstants.KEY_U;
    }

    static int defaultCompareOverlayKey() {
        return InputConstants.KEY_H;
    }

    static boolean canOpenDashboard(boolean normalPlay) {
        return normalPlay;
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

    private static KeyMapping mapping(String translationKey, int key) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    public static List<Shortcut> shortcuts(KeyMapping[] mappings) {
        return List.of(
                shortcut("luma.hotkeys.open_workspace", "luma.hotkeys.open_workspace_help",
                        mapping(mappings, "key.lumi.open_dashboard"), false),
                shortcut("luma.hotkeys.quick_save", "luma.hotkeys.quick_save_help",
                        mapping(mappings, "key.lumi.quick_save"), true),
                shortcut("luma.hotkeys.undo", "luma.hotkeys.undo_help",
                        mapping(mappings, "key.lumi.undo"), true),
                shortcut("luma.hotkeys.redo", "luma.hotkeys.redo_help",
                        mapping(mappings, "key.lumi.redo"), true),
                shortcut("luma.hotkeys.compare_overlay", "luma.hotkeys.compare_overlay_help",
                        mapping(mappings, "key.lumi.toggle_compare_overlay"), false),
                shortcut("luma.hotkeys.action_modifier",
                        "luma.hotkeys.action_modifier_help",
                        mapping(mappings, "key.lumi.action_modifier"), false),
                shortcut("luma.hotkeys.quick_rollback",
                        "luma.hotkeys.quick_rollback_help",
                        mapping(mappings, "key.lumi.quick_rollback"), false),
                shortcut("luma.hotkeys.shortcut_info",
                        "luma.hotkeys.shortcut_info_help",
                        mapping(mappings, "key.lumi.hotkey_info"), true));
    }

    private static KeyMapping mapping(KeyMapping[] mappings, String name) {
        for (KeyMapping mapping : mappings) {
            if (name.equals(mapping.getName())) {
                return mapping;
            }
        }
        throw new IllegalStateException("Missing Lumi key mapping: " + name);
    }

    public static boolean actionModifierDown(KeyMapping[] mappings) {
        return mapping(mappings, "key.lumi.action_modifier").isDown();
    }

    public static String bindingLabel(KeyMapping[] mappings, String name) {
        return mapping(mappings, name).getTranslatedKeyMessage().getString();
    }

    public static boolean bindingDown(KeyMapping[] mappings, String name) {
        return mapping(mappings, name).isDown();
    }

    public static boolean bindingUnbound(KeyMapping[] mappings, String name) {
        return mapping(mappings, name).isUnbound();
    }

    private static Shortcut shortcut(
            String labelKey, String helpKey, KeyMapping key, boolean chorded) {
        return new Shortcut(
                labelKey, helpKey,
                key.getTranslatedKeyMessage().getString(), chorded);
    }

    public record Shortcut(
            String labelKey, String helpKey, String key, boolean chorded) {
        public String display(String modifier) {
            return chorded ? modifier + " + " + key : key;
        }
    }
}
