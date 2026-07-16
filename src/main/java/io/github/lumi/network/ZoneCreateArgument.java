package io.github.lumi.network;

import io.github.lumi.domain.model.BlockBox;
import java.util.Objects;

/** Bounded selection used to create project-scoped zone metadata. */
public record ZoneCreateArgument(String name, BlockBox area) {
    private static final int MAX_NAME_LENGTH = 256;

    public ZoneCreateArgument {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(area, "area");
        name = name.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid zone name");
        }
    }

    public String encode() {
        return name + "\n" + area.minX() + "," + area.minY() + "," + area.minZ()
                + "," + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }

    public static ZoneCreateArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid zone create argument");
        }
        String[] coordinates = encoded.substring(separator + 1).split(",", -1);
        if (coordinates.length != 6) {
            throw new IllegalArgumentException("Invalid zone selection");
        }
        try {
            return new ZoneCreateArgument(encoded.substring(0, separator), new BlockBox(
                    Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]), Integer.parseInt(coordinates[3]),
                    Integer.parseInt(coordinates[4]), Integer.parseInt(coordinates[5])));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid zone selection", invalid);
        }
    }
}
