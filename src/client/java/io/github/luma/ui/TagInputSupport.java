package io.github.luma.ui;

import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.wispforest.owo.ui.component.TextBoxComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class TagInputSupport {

    public static final int MAX_LENGTH = 128;

    private TagInputSupport() {
    }

    public static String limit(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH);
    }

    public static List<String> knownTags(List<ProjectVersion> versions) {
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            tags.addAll(ProjectVersionTags.from(version));
        }
        return List.copyOf(tags);
    }

    public static void configure(TextBoxComponent input, String text, List<String> knownTags, boolean appendComma) {
        input.setMaxLength(MAX_LENGTH);
        input.setSuggestion(null);
    }

    public static String acceptInto(TextBoxComponent input, String text, List<String> knownTags, boolean appendComma) {
        String accepted = acceptSuggestion(text, knownTags, appendComma);
        input.setValue(accepted);
        input.setCursorPosition(accepted.length());
        configure(input, accepted, knownTags, appendComma);
        return accepted;
    }

    public static String acceptSuggestion(String text, List<String> knownTags, boolean appendComma) {
        String suggestion = suggestion(text, knownTags);
        if (suggestion.isBlank()) {
            return limit(text);
        }
        return acceptSuggestion(text, suggestion, appendComma);
    }

    public static String acceptSuggestion(String text, String suggestion, boolean appendComma) {
        if (suggestion == null || suggestion.isBlank()) {
            return limit(text);
        }
        int comma = text == null ? -1 : text.lastIndexOf(',');
        String prefix = comma < 0 ? "" : text.substring(0, comma + 1).trim() + " ";
        return limit(prefix + suggestion + (appendComma ? ", " : ""));
    }

    public static boolean hasSuggestion(String text, List<String> knownTags) {
        return !suggestion(text, knownTags).isBlank();
    }

    public static List<String> suggestions(String text, List<String> knownTags, int limit) {
        String token = currentToken(text);
        if (token.isBlank() || knownTags == null || knownTags.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> existing = ProjectVersionTags.parse(text);
        String normalizedToken = token.toLowerCase(Locale.ROOT);
        ArrayList<String> prefix = new ArrayList<>();
        ArrayList<String> fuzzy = new ArrayList<>();
        for (String tag : knownTags) {
            if (tag == null || tag.isBlank() || existing.contains(tag)) {
                continue;
            }
            String normalizedTag = tag.toLowerCase(Locale.ROOT);
            if (normalizedTag.equals(normalizedToken)) {
                continue;
            }
            if (normalizedTag.startsWith(normalizedToken)) {
                prefix.add(tag);
            } else if (fuzzyMatches(normalizedTag, normalizedToken)) {
                fuzzy.add(tag);
            }
        }

        ArrayList<String> result = new ArrayList<>(limit);
        for (String tag : prefix) {
            result.add(tag);
            if (result.size() == limit) {
                return List.copyOf(result);
            }
        }
        for (String tag : fuzzy) {
            result.add(tag);
            if (result.size() == limit) {
                return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    private static String suggestion(String text, List<String> knownTags) {
        String token = currentToken(text);
        if (token.isBlank() || knownTags == null || knownTags.isEmpty()) {
            return "";
        }
        List<String> existing = ProjectVersionTags.parse(text);
        String normalizedToken = token.toLowerCase(Locale.ROOT);
        String fuzzy = "";
        for (String tag : knownTags) {
            if (existing.contains(tag)) {
                continue;
            }
            String normalizedTag = tag.toLowerCase(Locale.ROOT);
            if (normalizedTag.equals(normalizedToken)) {
                return "";
            }
            if (normalizedTag.startsWith(normalizedToken)) {
                return tag;
            }
            if (fuzzy.isBlank() && fuzzyMatches(normalizedTag, normalizedToken)) {
                fuzzy = tag;
            }
        }
        return fuzzy;
    }

    private static String currentToken(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int comma = text.lastIndexOf(',');
        String token = comma < 0 ? text : text.substring(comma + 1);
        return token.trim().replaceFirst("^#+", "");
    }

    private static boolean fuzzyMatches(String candidate, String token) {
        int tokenIndex = 0;
        for (int i = 0; i < candidate.length() && tokenIndex < token.length(); i++) {
            if (candidate.charAt(i) == token.charAt(tokenIndex)) {
                tokenIndex++;
            }
        }
        return tokenIndex == token.length();
    }
}
