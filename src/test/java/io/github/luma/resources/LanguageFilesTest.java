package io.github.luma.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LanguageFilesTest {

    private static final Path LANGUAGE_DIR = Path.of("src/main/resources/assets/lumi/lang");
    private static final Set<String> REQUIRED_LOCALES = Set.of(
            "de_de.json",
            "en_us.json",
            "es_es.json",
            "fi_fi.json",
            "fr_fr.json",
            "ru_ru.json"
    );
    private static final Set<String> LOCALIZED_LOCALES = Set.of(
            "de_de.json",
            "es_es.json",
            "fi_fi.json",
            "fr_fr.json",
            "ru_ru.json"
    );
    private static final Set<String> CHECKLIST_EXACT_COPY_FORBIDDEN_KEYS = Set.of("luma.zones.list_title");
    private static final Set<String> EXACT_ENGLISH_COPY_ALLOWED_KEYS = Set.of(
            "key.category.lumi.general",
            "luma.action.buy_me_a_coffee",
            "luma.window.support",
            "luma.history.version_header",
            "luma.variant.entry",
            "luma.log.entry_header",
            "luma.integrity.error",
            "luma.compare.block_entry",
            "luma.compare.material_entry",
            "luma.settings.hud_title",
            "luma.window.mod_version",
            "luma.cleanup.candidate",
            "luma.variants.base_badge",
            "luma.ideas.zone_badge",
            "luma.ideas.switch_key",
            "luma.onboarding.step",
            "luma.tab.zones",
            "luma.zones.list_title",
            "luma.zones.history_item",
            "luma.actionbar.zone_entered"
    );
    private static final Pattern FORMAT_TOKEN = Pattern.compile("%(?:\\d+\\$)?[sd]|%%");
    private static final Pattern BACKTICK_TOKEN = Pattern.compile("`[^`]*`");
    private static final Pattern ENGLISH_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern LOCALIZATION_ARTIFACT = Pattern.compile(
            "LUMITOKEN|XQZ\\d+ZXQ|\\u27E6|\\u27E7|\\u0420[\\u2019\\u045F\\u0491]|\\u0421[\\u040A\\u2026]"
    );
    private static final Pattern STALE_ENGLISH_PHRASE = Pattern.compile(
            "\\b(No Lumi project|Lumi selection|Hold to preview|Lumi gives this build|"
                    + "Save build is your checkpoint|Native undo command|There is no tracked action|"
                    + "Quick save from the world|Restore changes the world|Restore asks|workspace|Bind|From|Enter)\\b"
    );
    private static final Pattern USER_FACING_VARIANT_TERM = Pattern.compile("\\bvariants?\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void shippedLanguagesContainAllEnglishKeys() throws IOException {
        Map<String, String> english = readLanguageFile("en_us.json");

        for (String fileName : REQUIRED_LOCALES) {
            Map<String, String> language = readLanguageFile(fileName);

            Assertions.assertTrue(
                    language.keySet().containsAll(english.keySet()),
                    fileName + " is missing keys: " + missingKeys(english, language)
            );
        }
    }

    @Test
    void shippedLanguagesPreserveFormatTokens() throws IOException {
        Map<String, String> english = readLanguageFile("en_us.json");

        for (String fileName : REQUIRED_LOCALES) {
            Map<String, String> language = readLanguageFile(fileName);
            for (Map.Entry<String, String> entry : english.entrySet()) {
                String translated = language.get(entry.getKey());
                Assertions.assertNotNull(translated, fileName + " missing " + entry.getKey());
                Assertions.assertFalse(translated.isBlank(), fileName + " has blank value for " + entry.getKey());
                Assertions.assertEquals(
                        formatTokenCounts(entry.getValue()),
                        formatTokenCounts(translated),
                        fileName + " changed format tokens for " + entry.getKey()
                );
                Assertions.assertEquals(
                        matchedTokens(entry.getValue(), BACKTICK_TOKEN),
                        matchedTokens(translated, BACKTICK_TOKEN),
                        fileName + " changed backtick tokens for " + entry.getKey()
                );
            }
        }
    }

    @Test
    void localizedLanguagesDoNotKeepEnglishUiText() throws IOException {
        Map<String, String> english = readLanguageFile("en_us.json");
        List<String> failures = new ArrayList<>();

        for (String fileName : LOCALIZED_LOCALES) {
            Map<String, String> language = readLanguageFile(fileName);
            for (Map.Entry<String, String> entry : language.entrySet()) {
                String value = entry.getValue();
                if (LOCALIZATION_ARTIFACT.matcher(value).find() || STALE_ENGLISH_PHRASE.matcher(value).find()) {
                    failures.add(fileName + " " + entry.getKey() + " = " + value);
                    continue;
                }
                String englishValue = english.get(entry.getKey());
                if (englishValue != null
                        && value.equals(englishValue)
                        && value.length() > 2
                        && ENGLISH_LETTER.matcher(value).find()
                        && !EXACT_ENGLISH_COPY_ALLOWED_KEYS.contains(entry.getKey())) {
                    failures.add(fileName + " " + entry.getKey() + " = " + value);
                }
            }
        }

        Assertions.assertTrue(
                failures.isEmpty(),
                "Localized UI text should not keep English values: " + failures
        );
    }

    @Test
    void russianAndSpanishChecklistKeysCannotUseAllowedEnglishCopies() throws IOException {
        Map<String, String> english = readLanguageFile("en_us.json");
        List<String> failures = new ArrayList<>();

        for (String fileName : List.of("ru_ru.json", "es_es.json")) {
            Map<String, String> language = readLanguageFile(fileName);
            for (String key : CHECKLIST_EXACT_COPY_FORBIDDEN_KEYS) {
                String translated = language.get(key);
                Assertions.assertNotNull(translated, fileName + " missing " + key);
                if (translated.equals(english.get(key))) {
                    failures.add(fileName + " " + key + " = " + translated);
                }
            }
        }

        Assertions.assertTrue(
                failures.isEmpty(),
                "Checklist keys should not use allowed English copies in RU/ES: " + failures
        );
    }

    @Test
    void englishLanguageUsesBranchTerminologyInUserFacingText() throws IOException {
        Map<String, String> english = readLanguageFile("en_us.json");

        List<String> variantValues = english.entrySet().stream()
                .filter(entry -> USER_FACING_VARIANT_TERM.matcher(entry.getValue()).find())
                .map(entry -> entry.getKey() + " = " + entry.getValue())
                .sorted()
                .toList();

        Assertions.assertTrue(
                variantValues.isEmpty(),
                "English UI text should use branch wording instead of variant: " + variantValues
        );
    }

    private static Map<String, String> readLanguageFile(String fileName) throws IOException {
        Path file = LANGUAGE_DIR.resolve(fileName);
        JsonObject object = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsString());
        }
        return values;
    }

    private static List<String> missingKeys(Map<String, String> english, Map<String, String> language) {
        return english.keySet().stream()
                .filter(key -> !language.containsKey(key))
                .sorted()
                .toList();
    }

    private static Map<String, Integer> formatTokenCounts(String value) {
        Map<String, Integer> counts = new HashMap<>();
        for (String token : matchedTokens(value, FORMAT_TOKEN)) {
            String normalized = token.equals("%%") ? token : token.substring(token.length() - 1);
            counts.merge(normalized, 1, Integer::sum);
        }
        return counts;
    }

    private static List<String> matchedTokens(String value, Pattern pattern) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches;
    }
}
