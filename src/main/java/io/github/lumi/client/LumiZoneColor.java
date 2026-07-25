package io.github.lumi.client;

/** Resolves the persisted zone color and the opt-in jeb_ rainbow presentation. */
public final class LumiZoneColor {
    private static final long CYCLE_MILLIS = 6_000L;

    public int resolve(String name, int persistedColor, long nowMillis) {
        if (!"jeb_".equals(name)) return persistedColor;
        float hue = Math.floorMod(nowMillis, CYCLE_MILLIS)
                / (float) CYCLE_MILLIS * 6.0F;
        int sector = (int) hue;
        int blend = Math.round((hue - sector) * 255.0F);
        int inverse = 255 - blend;
        int rgb = switch (sector) {
            case 0 -> 0xff0000 | blend << 8;
            case 1 -> inverse << 16 | 0xff00;
            case 2 -> 0xff00 | blend;
            case 3 -> inverse << 8 | 0xff;
            case 4 -> blend << 16 | 0xff;
            default -> 0xff0000 | inverse;
        };
        return 0xff000000 | rgb;
    }
}
