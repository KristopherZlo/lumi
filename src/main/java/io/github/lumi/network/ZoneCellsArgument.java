package io.github.lumi.network;

import io.github.lumi.domain.model.BlockBox;
import java.util.Objects;

/** Bounded wooden-sword intent for adding or removing 16-cubed zone cells. */
public record ZoneCellsArgument(boolean add, BlockBox area) {
    public ZoneCellsArgument {
        Objects.requireNonNull(area, "area");
    }

    public String encode() {
        return (add ? "add\n" : "remove\n")
                + area.minX() + "," + area.minY() + "," + area.minZ()
                + "," + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }

    public static ZoneCellsArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid zone cell argument");
        }
        boolean add = switch (encoded.substring(0, separator)) {
            case "add" -> true;
            case "remove" -> false;
            default -> throw new IllegalArgumentException("Invalid zone cell action");
        };
        String[] coordinates = encoded.substring(separator + 1).split(",", -1);
        if (coordinates.length != 6) {
            throw new IllegalArgumentException("Invalid zone cell area");
        }
        try {
            return new ZoneCellsArgument(add, new BlockBox(
                    Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]), Integer.parseInt(coordinates[3]),
                    Integer.parseInt(coordinates[4]), Integer.parseInt(coordinates[5])));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid zone cell area", invalid);
        }
    }
}
