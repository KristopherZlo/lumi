package io.github.lumi.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.LumiClient;
import io.github.lumi.client.ui.LumiBranchesScreen;
import io.github.lumi.client.ui.LumiDashboardScreen;
import io.github.lumi.client.ui.LumiMergeScreen;
import io.github.lumi.client.ui.LumiOnboardingScreen;
import io.github.lumi.client.ui.LumiRecoveryScreen;
import io.github.lumi.client.ui.LumiRestoreScreen;
import io.github.lumi.client.ui.LumiSaveScreen;
import io.github.lumi.client.ui.LumiSettingsScreen;
import io.github.lumi.client.ui.LumiVersionDetailsScreen;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Drives Lumi exactly through registered hotkeys and keyboard-focused UI controls. */
final class LumiUiTestDriver {
    private static final int MAX_FOCUS_STEPS = 256;

    private final ClientGameTestContext context;

    LumiUiTestDriver(ClientGameTestContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    void save(String name) {
        completeOnboardingIfShown();
        pressChord("key.lumi.quick_save");
        context.waitForScreen(LumiSaveScreen.class);
        typeIntoFocusedTextBox(LumiSaveScreen.class, name);
        pressUniqueButton(LumiSaveScreen.class, "luma.action.save_build");
        context.waitForScreen(null);
    }

    void restore(CommitId target) {
        String query = uniquePrefix(target);
        openDashboard();
        typeIntoFocusedTextBox(LumiDashboardScreen.class, query);
        pressFilteredHistoryAction("luma.action.restore");
        context.waitForScreen(LumiRestoreScreen.class);
        pressUniqueButton(LumiRestoreScreen.class, "luma.action.restore");
        context.waitForScreen(LumiDashboardScreen.class);
        closeScreen(LumiDashboardScreen.class, null);
    }

    void openVersionDetails(CommitId target) {
        openDashboard();
        typeIntoFocusedTextBox(LumiDashboardScreen.class, uniquePrefix(target));
        pressFilteredHistoryAction("luma.action.open_details");
        context.waitForScreen(LumiVersionDetailsScreen.class);
    }

    private void pressFilteredHistoryAction(String translationKey) {
        for (int tick = 0; tick < 1_200; tick++) {
            ButtonState state = buttonState(
                    LumiDashboardScreen.class, translationKey, -1);
            if (state.count() >= 1 && state.count() <= 2) {
                pressButton(LumiDashboardScreen.class, translationKey,
                        state.count() - 1, false);
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("Filtered History did not expose "
                + translationKey);
    }

    void createBranch(String name) {
        openBranches();
        typeIntoFocusedTextBox(LumiBranchesScreen.class, name);
        pressUniqueButton(LumiBranchesScreen.class, "luma.action.variant_create");
        context.waitForScreen(LumiDashboardScreen.class);
        closeScreen(LumiDashboardScreen.class, null);
    }

    void merge(int sourceButton) {
        openBranches();
        pressUniqueButton(LumiBranchesScreen.class, "luma.action.merge_into_current");
        context.waitForScreen(LumiMergeScreen.class);
        pressButton(LumiMergeScreen.class, "luma.action.preview", sourceButton, false);
        pressUniqueButton(LumiMergeScreen.class, "luma.action.merge_into_current");
        context.waitForScreen(LumiBranchesScreen.class);
        closeBranches();
    }

    void resumeRecovery() {
        pressUniqueButton(LumiRecoveryScreen.class, "luma.action.recovery_restore");
        context.waitForScreen(null);
    }

    void completeOnboardingIfShown() {
        context.waitTick();
        if (!isScreen(LumiOnboardingScreen.class)) {
            return;
        }
        closeScreen(LumiOnboardingScreen.class, null);
    }

    void openDashboard() {
        completeOnboardingIfShown();
        pressStandalone("key.lumi.open_dashboard");
        context.waitForScreen(LumiDashboardScreen.class);
    }

    void awaitHistory() {
        for (int tick = 0; tick < 12_000; tick++) {
            if (context.computeOnClient(client ->
                    LumiClient.history().state().snapshot().isPresent())) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("Lumi history did not synchronize");
    }

    void disablePreviewGeneration() {
        disableSetting(
                "luma.settings.preview_generation",
                HistorySnapshotPayload.WorkspaceView::previewGenerationEnabled);
    }

    void disableEntityRestore() {
        disableSetting(
                "luma.settings.restore_entities",
                HistorySnapshotPayload.WorkspaceView::includeEntitiesOnRestore);
    }

    private void disableSetting(
            String translationKey,
            Predicate<HistorySnapshotPayload.WorkspaceView> enabled) {
        if (!enabled.test(activeWorkspace())) return;
        openTab("luma.action.settings", LumiSettingsScreen.class);
        pressUniqueButton(LumiSettingsScreen.class, translationKey);
        for (int tick = 0; tick < 1_200; tick++) {
            if (!enabled.test(activeWorkspace())) {
                closeScreen(LumiSettingsScreen.class, LumiDashboardScreen.class);
                closeScreen(LumiDashboardScreen.class, null);
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(translationKey + " remained enabled");
    }

    void awaitZone(String name, boolean active) {
        for (int tick = 0; tick < 1_200; tick++) {
            boolean found = context.computeOnClient(client ->
                    LumiClient.history().state().snapshot().stream()
                            .flatMap(snapshot -> snapshot.zones().stream())
                            .anyMatch(zone -> zone.name().equals(name)
                                    && zone.active() == active));
            if (found) return;
            context.waitTick();
        }
        throw new AssertionError("Lumi zone did not become "
                + (active ? "active: " : "inactive: ") + name);
    }

    <T extends Screen> void openTab(String translationKey, Class<T> screen) {
        openDashboard();
        pressUniqueButton(LumiDashboardScreen.class, translationKey);
        context.waitForScreen(screen);
    }

    void assertButton(
            Class<? extends Screen> screen, String translationKey, boolean active) {
        ButtonState state = buttonState(screen, translationKey, 0);
        if (state.count() != 1 || state.active() != active) {
            throw new AssertionError(screen.getSimpleName() + " expected one "
                    + (active ? "enabled " : "disabled ") + translationKey
                    + " but found count=" + state.count() + " active="
                    + state.active() + "; " + state.diagnostic());
        }
    }

    void assertButtonEventually(
            Class<? extends Screen> screen, String translationKey, boolean active) {
        for (int tick = 0; tick < 1_200; tick++) {
            ButtonState state = buttonState(screen, translationKey, 0);
            if (state.count() == 1 && state.active() == active) {
                return;
            }
            context.waitTick();
        }
        assertButton(screen, translationKey, active);
    }

    void assertButtonCount(
            Class<? extends Screen> screen, String translationKey, int expected) {
        ButtonState state = buttonState(screen, translationKey, -1);
        if (state.count() != expected) {
            throw new AssertionError(screen.getSimpleName() + " expected "
                    + expected + " buttons for " + translationKey + " but found "
                    + state.count() + "; " + state.diagnostic());
        }
    }

    void assertButtonCountEventually(
            Class<? extends Screen> screen, String translationKey, int expected) {
        for (int tick = 0; tick < 1_200; tick++) {
            if (buttonState(screen, translationKey, -1).count() == expected) {
                return;
            }
            context.waitTick();
        }
        assertButtonCount(screen, translationKey, expected);
    }

    void assertButtonStartingWith(
            Class<? extends Screen> screen, String translationKey, boolean active) {
        String prefix = Component.translatable(translationKey).getString();
        ButtonState state = context.computeOnClient(client -> {
            if (!screen.isInstance(client.screen)) {
                return new ButtonState(0, false, false, "screen changed");
            }
            List<Button> matches = client.screen.children().stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button.getMessage().getString()
                            .startsWith(prefix))
                    .toList();
            if (matches.isEmpty()) {
                return new ButtonState(0, false, false,
                        diagnostic(client.screen));
            }
            Button target = matches.getFirst();
            return new ButtonState(matches.size(),
                    client.screen.getFocused() == target, target.active,
                    diagnostic(client.screen));
        });
        if (state.count() != 1 || state.active() != active) {
            throw new AssertionError(screen.getSimpleName() + " expected one "
                    + (active ? "enabled " : "disabled ") + translationKey
                    + " prefix but found count=" + state.count() + " active="
                    + state.active() + "; " + state.diagnostic());
        }
    }

    void assertButtonStates(
            Class<? extends Screen> screen,
            String translationKey,
            int enabled,
            int disabled) {
        String label = Component.translatable(translationKey).getString();
        int[] counts = context.computeOnClient(client -> {
            if (!screen.isInstance(client.screen)) return new int[] {0, 0};
            List<Button> matches = client.screen.children().stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals(label))
                    .toList();
            return new int[] {
                    (int) matches.stream().filter(button -> button.active).count(),
                    (int) matches.stream().filter(button -> !button.active).count()
            };
        });
        if (counts[0] != enabled || counts[1] != disabled) {
            throw new AssertionError(screen.getSimpleName() + " expected "
                    + enabled + " enabled and " + disabled + " disabled "
                    + translationKey + " buttons, got " + counts[0] + " and "
                    + counts[1]);
        }
    }

    void assertFocusedText(
            Class<? extends Screen> screen, String expected) {
        String actual = context.computeOnClient(client -> {
            if (!screen.isInstance(client.screen)
                    || !(client.screen.getFocused() instanceof EditBox field)) {
                return null;
            }
            return field.getValue();
        });
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(screen.getSimpleName() + " expected focused text '"
                    + expected + "' but got '" + actual + "'");
        }
    }

    int firstActiveButton(
            Class<? extends Screen> screen, String translationKey) {
        String label = Component.translatable(translationKey).getString();
        return context.computeOnClient(client -> {
            if (!screen.isInstance(client.screen)) return -1;
            List<Button> matches = client.screen.children().stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals(label))
                    .toList();
            for (int index = 0; index < matches.size(); index++) {
                if (matches.get(index).active) return index;
            }
            return -1;
        });
    }

    void pressStandalone(String mappingName) {
        requireScreen(null);
        context.getInput().pressKey(mapping(mappingName));
    }

    void pressChord(String mappingName) {
        requireScreen(null);
        KeyMapping mapping = mapping(mappingName);
        context.getInput().holdAlt();
        try {
            context.getInput().pressKey(mapping);
        } finally {
            context.getInput().releaseAlt();
        }
    }

    private KeyMapping mapping(String mappingName) {
        return context.computeOnClient(client -> {
            if (client.player == null) {
                throw new AssertionError("Lumi hotkey requires a player: " + mappingName);
            }
            for (KeyMapping candidate : client.options.keyMappings) {
                if (candidate.getName().equals(mappingName)) {
                    return candidate;
                }
            }
            throw new AssertionError("Missing Lumi key mapping: " + mappingName);
        });
    }

    private void openBranches() {
        openDashboard();
        pressUniqueButton(LumiDashboardScreen.class, "luma.tab.variants");
        context.waitForScreen(LumiBranchesScreen.class);
    }

    private void closeBranches() {
        if (isScreen(LumiBranchesScreen.class)) {
            closeScreen(LumiBranchesScreen.class, LumiDashboardScreen.class);
        }
        closeScreen(LumiDashboardScreen.class, null);
    }

    private HistorySnapshotPayload.WorkspaceView activeWorkspace() {
        return context.computeOnClient(client -> LumiClient.history().state()
                .snapshot().orElseThrow().workspaces().stream()
                .filter(HistorySnapshotPayload.WorkspaceView::active)
                .findFirst().orElseThrow());
    }

    void closeScreen(
            Class<? extends Screen> current, Class<? extends Screen> next) {
        requireScreen(current);
        context.waitTick();
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(next);
    }

    void typeIntoFocusedTextBox(
            Class<? extends Screen> expectedScreen, String text) {
        requireScreen(expectedScreen);
        for (int step = 0; step < MAX_FOCUS_STEPS; step++) {
            boolean focused = context.computeOnClient(client ->
                    expectedScreen.isInstance(client.screen)
                            && client.screen.getFocused() instanceof EditBox);
            if (focused) {
                context.getInput().typeChars(text);
                context.waitTick();
                String actual = context.computeOnClient(client ->
                        ((EditBox) client.screen.getFocused()).getValue());
                if (!actual.equals(text)) {
                    throw new AssertionError(expectedScreen.getSimpleName()
                            + " received '" + actual + "' instead of '" + text + "'");
                }
                return;
            }
            context.getInput().pressKey(InputConstants.KEY_TAB);
        }
        throw new AssertionError("Could not focus the text box in "
                + expectedScreen.getSimpleName());
    }

    void pressUniqueButton(
            Class<? extends Screen> expectedScreen, String translationKey) {
        pressButton(expectedScreen, translationKey, 0, true);
    }

    void pressUniqueButtonAfterScrolling(
            Class<? extends Screen> expectedScreen, String translationKey) {
        for (int step = 0; step < 32; step++) {
            if (buttonState(expectedScreen, translationKey, 0).count() == 1) {
                pressUniqueButton(expectedScreen, translationKey);
                return;
            }
            scrollDown(expectedScreen);
        }
        throw new AssertionError("Could not reveal button " + translationKey
                + " in " + expectedScreen.getSimpleName());
    }

    void scrollDown(Class<? extends Screen> expectedScreen) {
        requireScreen(expectedScreen);
        boolean consumed = context.computeOnClient(client -> client.screen.mouseScrolled(
                client.screen.width * 0.75,
                client.screen.height * 0.5,
                0.0, -1.0));
        if (!consumed) {
            throw new AssertionError(expectedScreen.getSimpleName()
                    + " did not consume downward scroll");
        }
        context.waitTick();
    }

    void pressButton(
            Class<? extends Screen> expectedScreen,
            String translationKey,
            int index,
            boolean unique) {
        requireScreen(expectedScreen);
        for (int step = 0; step < MAX_FOCUS_STEPS; step++) {
            ButtonState state = buttonState(expectedScreen, translationKey, index);
            if (state.count() == 0 || (!unique && state.count() <= index)) {
                context.waitTick();
                continue;
            }
            if (unique && state.count() != 1) {
                throw new AssertionError(expectedScreen.getSimpleName() + " exposed "
                        + state.count() + " buttons for " + translationKey
                        + "; " + state.diagnostic());
            }
            if (!state.active()) {
                context.waitTick();
                continue;
            }
            if (state.focused()) {
                context.getInput().pressKey(InputConstants.KEY_RETURN);
                return;
            }
            context.getInput().pressKey(InputConstants.KEY_TAB);
        }
        throw new AssertionError("Could not focus button " + translationKey
                + " at index " + index + " in " + expectedScreen.getSimpleName());
    }

    private ButtonState buttonState(
            Class<? extends Screen> expectedScreen, String translationKey, int targetIndex) {
        return context.computeOnClient(client -> {
            if (!expectedScreen.isInstance(client.screen)) {
                return new ButtonState(0, false, false, "screen changed");
            }
            String label = Component.translatable(translationKey).getString();
            List<Button> matches = client.screen.children().stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals(label))
                    .toList();
            if (targetIndex < 0 || targetIndex >= matches.size()) {
                return new ButtonState(matches.size(), false, false,
                        diagnostic(client.screen));
            }
            Button target = matches.get(targetIndex);
            return new ButtonState(matches.size(), client.screen.getFocused() == target,
                    target.active, diagnostic(client.screen));
        });
    }

    private String diagnostic(Screen screen) {
        String fields = screen.children().stream()
                .filter(EditBox.class::isInstance)
                .map(EditBox.class::cast)
                .map(EditBox::getValue)
                .toList().toString();
        String buttons = screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .map(button -> button.getMessage().getString())
                .toList().toString();
        return "fields=" + fields + ", buttons=" + buttons;
    }

    private String uniquePrefix(CommitId target) {
        String hex = target.hex();
        return hex.substring(0, Math.min(12, hex.length()));
    }

    void requireScreen(Class<? extends Screen> expected) {
        boolean matches = expected == null
                ? context.computeOnClient(client -> client.screen == null)
                : isScreen(expected);
        if (!matches) {
            String actual = context.computeOnClient(client -> client.screen == null
                    ? "none" : client.screen.getClass().getSimpleName());
            throw new AssertionError("Expected "
                    + (expected == null ? "normal play" : expected.getSimpleName())
                    + " but found " + actual);
        }
    }

    private boolean isScreen(Class<? extends Screen> expected) {
        return context.computeOnClient(client -> expected.isInstance(client.screen));
    }

    private record ButtonState(
            int count, boolean focused, boolean active, String diagnostic) { }
}
