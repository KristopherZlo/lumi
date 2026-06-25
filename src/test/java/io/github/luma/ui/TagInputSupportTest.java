package io.github.luma.ui;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagInputSupportTest {

    @Test
    void limitsTagInputTo128Characters() {
        String longInput = "a".repeat(160);

        assertEquals(128, TagInputSupport.limit(longInput).length());
    }

    @Test
    void acceptedSuggestionKeepsTheInputLimit() {
        String prefix = "a".repeat(126) + ", ro";

        String accepted = TagInputSupport.acceptSuggestion(prefix, List.of("roof"), true);

        assertTrue(accepted.length() <= 128);
    }

    @Test
    void suggestionsPreferMissingPrefixMatches() {
        assertEquals(
                List.of("castle"),
                TagInputSupport.suggestions("roof, ca", List.of("roof", "castle"), 4)
        );
    }
}
