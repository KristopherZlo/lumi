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
    private static final Pattern FORMAT_TOKEN = Pattern.compile("%(?:\\d+\\$)?[sd]|%%");
    private static final Pattern BACKTICK_TOKEN = Pattern.compile("`[^`]*`");

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
