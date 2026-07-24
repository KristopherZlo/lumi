package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OnboardingLocalizationTest {
    private static final Path LANGUAGES =
            Path.of("src/main/resources/assets/lumi/lang");
    private static final List<String> SHARED_KEYS = List.of(
            "luma.action.start",
            "luma.action.finish",
            "luma.onboarding.header",
            "luma.onboarding.key_unbound",
            "luma.onboarding.press_open",
            "luma.onboarding.press_quick_save",
            "luma.onboarding.press_info",
            "luma.onboarding.world_edit_counter",
            "luma.onboarding.preview_changes_hold",
            "luma.onboarding.undo_redo_undo",
            "luma.onboarding.undo_redo_undo_help",
            "luma.onboarding.undo_redo_redo",
            "luma.onboarding.undo_redo_redo_help",
            "luma.onboarding.undo_redo_observe_help");

    @Test
    void everyLocaleCoversTheActiveTour() throws IOException {
        try (var files = Files.list(LANGUAGES)) {
            for (Path file : files.filter(path ->
                    path.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject translations = JsonParser.parseReader(
                        Files.newBufferedReader(file)).getAsJsonObject();
                for (String id : OnboardingTour.pageIds()) {
                    assertKey(translations, file, "luma.onboarding.topic_" + id);
                    assertKey(translations, file, "luma.onboarding." + id + "_help");
                }
                for (String key : SHARED_KEYS) {
                    assertKey(translations, file, key);
                }
            }
        }
    }

    private static void assertKey(
            JsonObject translations, Path file, String key) {
        assertTrue(translations.has(key),
                () -> file.getFileName() + " is missing " + key);
    }
}
