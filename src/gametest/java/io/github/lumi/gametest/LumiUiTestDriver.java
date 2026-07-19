package io.github.lumi.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.LumiClient;
import io.github.lumi.client.ui.LumiBranchScreen;
import io.github.lumi.client.ui.LumiBranchesScreen;
import io.github.lumi.client.ui.LumiDashboardScreen;
import io.github.lumi.client.ui.LumiMergeScreen;
import io.github.lumi.client.ui.LumiOnboardingScreen;
import io.github.lumi.client.ui.LumiRecoveryScreen;
import io.github.lumi.client.ui.LumiRestoreScreen;
import io.github.lumi.client.ui.LumiSaveScreen;
import io.github.lumi.domain.model.CommitId;
import java.util.List;
import java.util.Objects;
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
        context.waitTick();
        pressUniqueButton(LumiDashboardScreen.class, "luma.action.restore");
        context.waitForScreen(LumiRestoreScreen.class);
        pressUniqueButton(LumiRestoreScreen.class, "luma.action.restore_whole_save");
        context.waitForScreen(LumiDashboardScreen.class);
        closeScreen(LumiDashboardScreen.class, null);
    }

    void createBranch(String name) {
        openBranches();
        pressUniqueButton(LumiBranchesScreen.class, "luma.action.variant_create");
        context.waitForScreen(LumiBranchScreen.class);
        typeIntoFocusedTextBox(LumiBranchScreen.class, name);
        pressUniqueButton(LumiBranchScreen.class, "luma.action.variant_create");
        context.waitForScreen(LumiBranchesScreen.class);
        closeBranches();
    }

    void merge(int sourceButton) {
        openBranches();
        pressUniqueButton(LumiBranchesScreen.class, "luma.action.merge_into_current");
        context.waitForScreen(LumiMergeScreen.class);
        pressButton(LumiMergeScreen.class, "luma.action.merge_into_current", sourceButton, false);
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
        for (int page = 0; page < MAX_FOCUS_STEPS; page++) {
            ButtonState finish = buttonState(
                    LumiOnboardingScreen.class, "luma.action.finish", 0);
            if (finish.count() == 1) {
                pressUniqueButton(LumiOnboardingScreen.class, "luma.action.finish");
                context.waitForScreen(null);
                return;
            }
            pressUniqueButton(LumiOnboardingScreen.class, "luma.action.next");
            context.waitTick();
        }
        throw new AssertionError("Lumi onboarding did not reach its Finish action");
    }

    void pressChord(String mappingName) {
        requireScreen(null);
        KeyMapping mapping = context.computeOnClient(client -> {
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
        context.getInput().holdAlt();
        try {
            context.getInput().pressKey(mapping);
        } finally {
            context.getInput().releaseAlt();
        }
    }

    private void openDashboard() {
        pressChord("key.lumi.open_dashboard");
        context.waitForScreen(LumiDashboardScreen.class);
    }

    private void openBranches() {
        openDashboard();
        pressUniqueButton(LumiDashboardScreen.class, "luma.tab.variants");
        context.waitForScreen(LumiBranchesScreen.class);
    }

    private void closeBranches() {
        closeScreen(LumiBranchesScreen.class, LumiDashboardScreen.class);
        closeScreen(LumiDashboardScreen.class, null);
    }

    private void closeScreen(
            Class<? extends Screen> current, Class<? extends Screen> next) {
        requireScreen(current);
        context.waitTick();
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(next);
    }

    private void typeIntoFocusedTextBox(
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

    private void pressUniqueButton(
            Class<? extends Screen> expectedScreen, String translationKey) {
        pressButton(expectedScreen, translationKey, 0, true);
    }

    private void pressButton(
            Class<? extends Screen> expectedScreen,
            String translationKey,
            int index,
            boolean unique) {
        requireScreen(expectedScreen);
        for (int step = 0; step < MAX_FOCUS_STEPS; step++) {
            ButtonState state = buttonState(expectedScreen, translationKey, index);
            if ((unique && state.count() != 1) || (!unique && state.count() <= index)) {
                throw new AssertionError(expectedScreen.getSimpleName() + " exposed "
                        + state.count() + " buttons for " + translationKey
                        + "; " + state.diagnostic());
            }
            if (!state.active()) {
                throw new AssertionError("Button is disabled: " + translationKey
                        + " at index " + index);
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
        String query = hex.substring(0, Math.min(32, hex.length()));
        List<String> commits = context.computeOnClient(client -> LumiClient.history().state()
                .snapshot().orElseThrow(() -> new AssertionError(
                        "Lumi history is not synchronized"))
                .versions().stream()
                .map(version -> version.id().hex())
                .toList());
        if (!commits.contains(hex)) {
            throw new AssertionError("Restore target is absent from history: " + hex);
        }
        long matches = commits.stream().filter(commit -> commit.startsWith(query)).count();
        if (matches != 1) {
            throw new AssertionError("Restore target prefix is not unique: " + query);
        }
        return query;
    }

    private void requireScreen(Class<? extends Screen> expected) {
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
