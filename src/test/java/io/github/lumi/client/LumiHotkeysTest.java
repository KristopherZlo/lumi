package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiHotkeysTest {
    @Test
    void dashboardUsesStandaloneUOnlyDuringNormalPlay() {
        assertEquals(InputConstants.KEY_U, LumiHotkeys.defaultDashboardKey());
        assertTrue(LumiHotkeys.canOpenDashboard(true));
        assertFalse(LumiHotkeys.canOpenDashboard(false));
    }

    @Test
    void compareHighlightKeepsTheHBinding() {
        assertEquals(InputConstants.KEY_H, LumiHotkeys.defaultCompareOverlayKey());
    }

    @Test
    void onboardingUsesXpAndLevelUpCompletionSounds() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));

        assertTrue(source.contains("SoundEvents.EXPERIENCE_ORB_PICKUP"));
        assertTrue(source.contains("SoundEvents.PLAYER_LEVELUP"));
        assertTrue(source.contains("SimpleSoundInstance.forUI(sound, 1.0F)"));
    }

    @Test
    void quickRollbackRequiresTheRemappableActionChord() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiHotkeys.java"));
        assertTrue(source.contains(
                "consume(rollback, canUseChord, HotkeyActionDispatcher.Action.QUICK_ROLLBACK)"));
        assertFalse(source.contains(
                "consume(rollback, normalPlay, HotkeyActionDispatcher.Action.QUICK_ROLLBACK)"));
    }

    @Test
    void actionModifierIsARealRemappableBinding() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiHotkeys.java"));
        assertTrue(source.contains("\"key.lumi.action_modifier\""));
        assertTrue(source.contains("actionModifier.isDown()"));
        assertTrue(source.contains(
                "KeyBindingHelper.registerKeyBinding(actionModifier)"));
        assertTrue(source.contains("publishOnboardingEdges(altDown)"));
        assertTrue(source.contains(
                "new OnboardingEvent.Shortcut(shortcut, pressed)"));
    }

    @Test
    void arbitraryAltKeysResolveExplicitPersistentAssignments() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));

        assertTrue(source.contains("BRANCH_SLOTS.branch(snapshot, keyCode)"));
        assertTrue(source.contains("BRANCH_SLOTS.synchronize(snapshot)"));
        assertFalse(source.contains("snapshot.branches().get(keyCode)"));
        String hotkeys = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiHotkeys.java"));
        assertTrue(hotkeys.contains("pollBranchKeys(client, canUseChord)"));
        assertTrue(hotkeys.contains("InputConstants.isKeyDown("));
    }

    @Test
    void onlyChordedShortcutsDisplayTheActionModifier() {
        var chorded = new LumiHotkeys.Shortcut(
                "label", "help", "S", true);
        var quickRestore = new LumiHotkeys.Shortcut(
                "label", "help", "R", true);

        assertEquals("Action + S", chorded.display("Action"));
        assertEquals("Action + R", quickRestore.display("Action"));
    }

    @Test
    void aRemappedDashboardConflictIsConsumedBeforeVanillaAdvancements() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiHotkeys.java"));

        assertTrue(source.contains("START_CLIENT_TICK"));
        assertTrue(source.contains("dashboard.same(client.options.keyAdvancements)"));
        assertTrue(source.contains("consume(client.options.keyAdvancements)"));
    }

    @Test
    void altSUsesTheZoneSaveModalWhileAZoneIsActive() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));

        assertTrue(source.contains("activeZone().ifPresentOrElse("));
        assertTrue(source.contains("LumiClient.openZoneSave("));
        assertTrue(source.contains("LumiSaveScreen.Scope.ZONE"));
        assertTrue(source.contains("NETWORKING.saveZone("));
    }
}
