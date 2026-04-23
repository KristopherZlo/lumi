package io.github.luma.gbreak.client;

public final class CorruptedTextGenerator {

    private static final char[] GLITCH_ALPHABET = {
            '\u00D8', '\u00C6', '\u00DF', '\u00A7', '\u20AC', '\u25A0', '\u2593', '\u2206', '\u0416', '\u042F', '\u2620'
    };

    private CorruptedTextGenerator() {
    }

    public static String corrupt(String input) {
        if (input == null || input.isBlank()) {
            return "###";
        }

        long phase = System.nanoTime() / 80_000_000L;
        StringBuilder builder = new StringBuilder(input.length() + 4);
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (Character.isWhitespace(current)) {
                builder.append(current);
                continue;
            }

            int mode = Math.floorMod(current + index + (int) phase, 6);
            if (mode <= 1) {
                builder.append(GLITCH_ALPHABET[Math.floorMod(current + index + (int) phase, GLITCH_ALPHABET.length)]);
            } else if (mode == 2) {
                builder.append(Character.toUpperCase(current));
            } else if (mode == 3) {
                builder.append(Character.toLowerCase(current));
            } else {
                builder.append(current);
            }
        }
        builder.append(" ~~");
        return builder.toString();
    }
}
