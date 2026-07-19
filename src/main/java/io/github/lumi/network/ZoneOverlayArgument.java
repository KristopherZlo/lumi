package io.github.lumi.network;

import java.util.Objects;

/** Selects the legacy focused or all-zones world overlay. */
public record ZoneOverlayArgument(Mode mode) {
    public ZoneOverlayArgument {
        Objects.requireNonNull(mode, "mode");
    }

    public String encode() {
        return mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ZoneOverlayArgument parse(String encoded) {
        return new ZoneOverlayArgument(switch (
                Objects.requireNonNull(encoded, "encoded")) {
            case "focused" -> Mode.FOCUSED;
            case "all" -> Mode.ALL;
            default -> throw new IllegalArgumentException(
                    "Invalid zone overlay mode");
        });
    }

    public enum Mode {
        FOCUSED,
        ALL
    }
}
