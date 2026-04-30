package io.github.luma.ui.onboarding;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.KeyMapping;

/**
 * Resolves Minecraft key bindings to the bundled dark pixel-key sprite sheets.
 */
public final class KeyGlyphResolver {

    private static final int DEFAULT_HEIGHT = 21;
    private static final Map<String, Integer> FRAME_WIDTHS = Map.ofEntries(
            entry("0", 19), entry("1", 19), entry("2", 19), entry("3", 19), entry("4", 19),
            entry("5", 19), entry("6", 19), entry("7", 19), entry("8", 19), entry("9", 19),
            entry("a", 19), entry("b", 19), entry("c", 19), entry("d", 19), entry("e", 19),
            entry("f", 19), entry("g", 19), entry("h", 19), entry("i", 19), entry("j", 19),
            entry("k", 19), entry("l", 19), entry("m", 19), entry("n", 19), entry("o", 19),
            entry("p", 19), entry("q", 19), entry("r", 19), entry("s", 19), entry("t", 19),
            entry("u", 19), entry("v", 19), entry("w", 19), entry("x", 19), entry("y", 19),
            entry("z", 19), entry("alt", 33), entry("altgr", 49), entry("arrowdown", 19),
            entry("arrowleft", 19), entry("arrowright", 19), entry("arrowup", 19),
            entry("backspace", 53), entry("backspacealternative", 53), entry("caps", 41),
            entry("closecurly", 19), entry("colon", 19), entry("ctrl", 41), entry("empty1", 19),
            entry("empty2", 41), entry("enter", 39), entry("enteralternative", 49),
            entry("greaterthan", 19), entry("lessthan", 19), entry("opencurly", 19),
            entry("pipe", 19), entry("plus", 19), entry("questionmark", 19), entry("quote", 19),
            entry("shift", 49), entry("shiftalternative", 49), entry("shiftbigger", 61),
            entry("space", 98), entry("spacealternative", 98), entry("tab", 33),
            entry("tabalternative", 33), entry("tilde", 19), entry("underscore", 19),
            entry("windows", 19)
    );
    private static final Map<String, String> SPECIAL_KEYS = Map.ofEntries(
            entry("key.keyboard.left.alt", "alt"),
            entry("key.keyboard.right.alt", "altgr"),
            entry("key.keyboard.left.control", "ctrl"),
            entry("key.keyboard.right.control", "ctrl"),
            entry("key.keyboard.left.shift", "shift"),
            entry("key.keyboard.right.shift", "shift"),
            entry("key.keyboard.left.win", "windows"),
            entry("key.keyboard.right.win", "windows"),
            entry("key.keyboard.space", "space"),
            entry("key.keyboard.tab", "tab"),
            entry("key.keyboard.enter", "enter"),
            entry("key.keyboard.return", "enter"),
            entry("key.keyboard.backspace", "backspace"),
            entry("key.keyboard.caps.lock", "caps"),
            entry("key.keyboard.down", "arrowdown"),
            entry("key.keyboard.left", "arrowleft"),
            entry("key.keyboard.right", "arrowright"),
            entry("key.keyboard.up", "arrowup"),
            entry("key.keyboard.apostrophe", "quote"),
            entry("key.keyboard.backslash", "pipe"),
            entry("key.keyboard.comma", "lessthan"),
            entry("key.keyboard.equal", "plus"),
            entry("key.keyboard.grave.accent", "tilde"),
            entry("key.keyboard.left.bracket", "opencurly"),
            entry("key.keyboard.minus", "underscore"),
            entry("key.keyboard.period", "greaterthan"),
            entry("key.keyboard.right.bracket", "closecurly"),
            entry("key.keyboard.semicolon", "colon"),
            entry("key.keyboard.slash", "questionmark")
    );

    private KeyGlyphResolver() {
    }

    public static Optional<KeyGlyph> resolve(KeyMapping key) {
        if (key == null || key.isUnbound()) {
            return Optional.empty();
        }
        return resolve(key.saveString());
    }

    static Optional<KeyGlyph> resolve(String saveString) {
        String spriteName = spriteName(saveString);
        Integer frameWidth = FRAME_WIDTHS.get(spriteName);
        if (frameWidth == null) {
            return Optional.empty();
        }
        int height = "enter".equals(spriteName) || "enteralternative".equals(spriteName) ? 36 : DEFAULT_HEIGHT;
        return Optional.of(new KeyGlyph(spriteName, frameWidth, height));
    }

    static String spriteName(String saveString) {
        InputConstants.Key key = InputConstants.getKey(saveString);
        String name = key.getName().toLowerCase(Locale.ROOT);
        String special = SPECIAL_KEYS.get(name);
        if (special != null) {
            return special;
        }
        if (!name.startsWith("key.keyboard.")) {
            return "";
        }
        String suffix = name.substring("key.keyboard.".length());
        return suffix.length() == 1 && Character.isLetterOrDigit(suffix.charAt(0)) ? suffix : "";
    }

    private static Map.Entry<String, Integer> entry(String key, int value) {
        return Map.entry(key, value);
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return Map.entry(key, value);
    }
}
