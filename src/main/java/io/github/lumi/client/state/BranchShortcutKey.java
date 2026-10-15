package io.github.lumi.client.state;

import com.mojang.blaze3d.platform.InputConstants;

/** Stores platform-independent key names and translates pre-SDL numeric preferences. */
public final class BranchShortcutKey {
    private BranchShortcutKey() { }

    static String encode(int code) {
        return InputConstants.Type.KEYSYM.getOrCreate(code).getName();
    }

    static int decode(String name) {
        var key = InputConstants.getKey(name);
        if (key.getType() != InputConstants.Type.KEYSYM) {
            throw new IllegalArgumentException("Branch shortcuts require keyboard keys");
        }
        return key.getValue();
    }

    static int legacy(int code) {
        //? if >=26.3 {
        /*if (code >= 65 && code <= 90) return InputConstants.KEY_A + code - 65;
        if (code >= 49 && code <= 57) return InputConstants.KEY_1 + code - 49;
        if (code >= 290 && code <= 301) return InputConstants.KEY_F1 + code - 290;
        if (code >= 302 && code <= 313) return InputConstants.KEY_F13 + code - 302;
        if (code >= 321 && code <= 329) return InputConstants.KEY_NUMPAD1 + code - 321;
        return switch (code) {
            case 48 -> InputConstants.KEY_0;
            case 282 -> InputConstants.KEY_NUMLOCK;
            case 320 -> InputConstants.KEY_NUMPAD0;
            case 330 -> InputConstants.KEY_NUMPADCOMMA;
            case 335 -> InputConstants.KEY_NUMPADENTER;
            case 336 -> InputConstants.KEY_NUMPADEQUALS;
            case 264 -> InputConstants.KEY_DOWN;
            case 263 -> InputConstants.KEY_LEFT;
            case 262 -> InputConstants.KEY_RIGHT;
            case 265 -> InputConstants.KEY_UP;
            case 334 -> InputConstants.KEY_ADD;
            case 39 -> InputConstants.KEY_APOSTROPHE;
            case 92 -> InputConstants.KEY_BACKSLASH;
            case 44 -> InputConstants.KEY_COMMA;
            case 61 -> InputConstants.KEY_EQUALS;
            case 96 -> InputConstants.KEY_GRAVE;
            case 91 -> InputConstants.KEY_LBRACKET;
            case 45 -> InputConstants.KEY_MINUS;
            case 332 -> InputConstants.KEY_MULTIPLY;
            case 46 -> InputConstants.KEY_PERIOD;
            case 93 -> InputConstants.KEY_RBRACKET;
            case 59 -> InputConstants.KEY_SEMICOLON;
            case 47 -> InputConstants.KEY_SLASH;
            case 32 -> InputConstants.KEY_SPACE;
            case 258 -> InputConstants.KEY_TAB;
            case 342 -> InputConstants.KEY_LALT;
            case 341 -> InputConstants.KEY_LCONTROL;
            case 340 -> InputConstants.KEY_LSHIFT;
            case 346 -> InputConstants.KEY_RALT;
            case 345 -> InputConstants.KEY_RCONTROL;
            case 344 -> InputConstants.KEY_RSHIFT;
            case 257 -> InputConstants.KEY_RETURN;
            case 256 -> InputConstants.KEY_ESCAPE;
            case 259 -> InputConstants.KEY_BACKSPACE;
            case 261 -> InputConstants.KEY_DELETE;
            case 269 -> InputConstants.KEY_END;
            case 268 -> InputConstants.KEY_HOME;
            case 260 -> InputConstants.KEY_INSERT;
            case 267 -> InputConstants.KEY_PAGEDOWN;
            case 266 -> InputConstants.KEY_PAGEUP;
            case 280 -> InputConstants.KEY_CAPSLOCK;
            case 284 -> InputConstants.KEY_PAUSE;
            case 281 -> InputConstants.KEY_SCROLLLOCK;
            case 283 -> InputConstants.KEY_PRINTSCREEN;
            case 343 -> InputConstants.KEY_LGUI;
            case 347 -> InputConstants.KEY_RGUI;
            default -> -1;
        };
        *///?} else {
        return code;
        //?}
    }

    public static boolean valid(int code) {
        //? if >=26.3 {
        /*return code > 0 && code < 512;
        *///?} else {
        return code >= 32 && code <= 348;
        //?}
    }
}
