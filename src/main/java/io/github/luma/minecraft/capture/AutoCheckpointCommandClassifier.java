package io.github.luma.minecraft.capture;

import java.util.Locale;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;

/**
 * Estimates vanilla command edit volumes before the command mutates the world.
 */
public final class AutoCheckpointCommandClassifier {

    private final int threshold;

    public AutoCheckpointCommandClassifier(int threshold) {
        this.threshold = Math.max(1, threshold);
    }

    public boolean shouldCheckpoint(String command, BlockPos origin) {
        OptionalInt volume = this.estimatedVolume(command, origin);
        return volume.isPresent() && volume.getAsInt() >= this.threshold;
    }

    OptionalInt estimatedVolume(String command, BlockPos origin) {
        if (command == null || command.isBlank()) {
            return OptionalInt.empty();
        }
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        String[] tokens = normalized.trim().split("\\s+");
        if (tokens.length < 7) {
            return OptionalInt.empty();
        }

        return switch (tokens[0].toLowerCase(Locale.ROOT)) {
            case "fill" -> this.regionVolume(tokens, 1, origin);
            case "clone" -> this.regionVolume(tokens, 1, origin);
            default -> OptionalInt.empty();
        };
    }

    private OptionalInt regionVolume(String[] tokens, int startIndex, BlockPos origin) {
        if (tokens.length < startIndex + 6) {
            return OptionalInt.empty();
        }
        int[] coordinates = new int[6];
        for (int index = 0; index < coordinates.length; index++) {
            OptionalInt parsed = this.parseCoordinate(tokens[startIndex + index], originCoordinate(origin, index));
            if (parsed.isEmpty()) {
                return OptionalInt.empty();
            }
            coordinates[index] = parsed.getAsInt();
        }

        long width = Math.abs((long) coordinates[3] - coordinates[0]) + 1L;
        long height = Math.abs((long) coordinates[4] - coordinates[1]) + 1L;
        long depth = Math.abs((long) coordinates[5] - coordinates[2]) + 1L;
        long volume = width * height * depth;
        return volume > Integer.MAX_VALUE ? OptionalInt.of(Integer.MAX_VALUE) : OptionalInt.of((int) volume);
    }

    private OptionalInt parseCoordinate(String token, int origin) {
        if (token == null || token.isBlank() || token.startsWith("^")) {
            return OptionalInt.empty();
        }
        try {
            if (token.startsWith("~")) {
                String offset = token.substring(1);
                return OptionalInt.of(origin + (offset.isBlank() ? 0 : (int) Math.floor(Double.parseDouble(offset))));
            }
            return OptionalInt.of((int) Math.floor(Double.parseDouble(token)));
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    private static int originCoordinate(BlockPos origin, int coordinateIndex) {
        BlockPos resolved = origin == null ? BlockPos.ZERO : origin;
        return switch (coordinateIndex % 3) {
            case 0 -> resolved.getX();
            case 1 -> resolved.getY();
            default -> resolved.getZ();
        };
    }
}
