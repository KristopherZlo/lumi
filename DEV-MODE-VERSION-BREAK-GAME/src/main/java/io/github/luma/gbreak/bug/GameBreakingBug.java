package io.github.luma.gbreak.bug;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public enum GameBreakingBug {
    NONE(-1, "off", "Disable every injected bug"),
    THE_GHOST(0, "theghost", "Block placements become short-lived block_display ghosts"),
    NO_BLOCK_UPDATES(1, "noblockupdates", "Player block interaction is swallowed before the world can react"),
    GHOST_PLAYER(2, "ghostplayer", "The player can move, but interaction logic stops processing"),
    SINGLE_CHUNK(3, "singlechunk", "The client only renders the chunk that currently contains the player"),
    CORRUPTED_BLOCKS(4, "corruptedblocks", "Visible blocks mutate into incorrect states one per tick"),
    GLOBAL_CORRUPTION(5, "globalcorruption", "Translations, UI, shaders, and camera settings become glitched"),
    PERFORMANCE_COLLAPSE(6, "performancecollapse", "Client and server start stalling and allocating garbage");

    private final int id;
    private final String commandToken;
    private final String description;

    GameBreakingBug(int id, String commandToken, String description) {
        this.id = id;
        this.commandToken = commandToken;
        this.description = description;
    }

    public int id() {
        return this.id;
    }

    public String commandToken() {
        return this.commandToken;
    }

    public String description() {
        return this.description;
    }

    public String displayLabel() {
        return this == NONE
                ? "off"
                : this.id + " - " + this.commandToken;
    }

    public static Optional<GameBreakingBug> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        try {
            int parsedId = Integer.parseInt(normalized);
            return Arrays.stream(values())
                    .filter(candidate -> candidate.id == parsedId)
                    .findFirst();
        } catch (NumberFormatException ignored) {
            return Arrays.stream(values())
                    .filter(candidate -> candidate.commandToken.equals(normalized))
                    .findFirst();
        }
    }

    public static List<String> commandSuggestions() {
        return Arrays.stream(values())
                .flatMap(candidate -> candidate.id >= 0
                        ? java.util.stream.Stream.of(candidate.commandToken, Integer.toString(candidate.id))
                        : java.util.stream.Stream.of(candidate.commandToken)
                )
                .distinct()
                .toList();
    }
}
