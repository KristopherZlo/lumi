package io.github.lumi.client.specialthanks;

import com.google.gson.Gson;
import io.github.lumi.LumiMod;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Reads the trusted credits bundled in the Lumi resource pack. */
public final class SpecialThanksCatalogSource {
    private static final String RESOURCE_PATH = "assets/lumi/special-thanks.json";
    // ponytail: two cards fit without scrolling; raise this only with a scrollable screen.
    private static final int MAX_PEOPLE = 2;
    private static final Gson GSON = new Gson();
    private static final List<SpecialThanksEntry> FALLBACK = List.of(
            new SpecialThanksEntry("ImZlo", "ImZlo", "", "",
                    "Creator and maintainer"));

    public List<SpecialThanksEntry> loadBundled() {
        try (InputStream stream = SpecialThanksCatalogSource.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                return FALLBACK;
            }
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception failed) {
            LumiMod.LOGGER.warn("Failed to load bundled Lumi Special Thanks catalog", failed);
            return FALLBACK;
        }
    }

    List<SpecialThanksEntry> parse(String json) {
        Catalog catalog = GSON.fromJson(Objects.requireNonNull(json, "json"), Catalog.class);
        if (catalog == null || catalog.schema() != 2 || catalog.people() == null) {
            return FALLBACK;
        }
        List<SpecialThanksEntry> people = catalog.people().stream()
                .filter(Objects::nonNull)
                .filter(SpecialThanksEntry::visible)
                .limit(MAX_PEOPLE)
                .toList();
        return people.isEmpty() ? FALLBACK : people;
    }

    private record Catalog(int schema, List<SpecialThanksEntry> people) { }
}
