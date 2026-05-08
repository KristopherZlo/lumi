package io.github.luma.client.input;

import java.util.List;

/**
 * User-facing shortcut combinations rendered by help and onboarding surfaces.
 */
public final class LumiShortcutCatalog {

    private static final List<Entry> ENTRIES = List.of(
            new Entry(
                    "luma.hotkeys.open_workspace",
                    "luma.hotkeys.open_workspace_help",
                    List.of(LumiClientKeyBindings.Role.OPEN_WORKSPACE)
            ),
            new Entry(
                    "luma.hotkeys.quick_save",
                    "luma.hotkeys.quick_save_help",
                    List.of(LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.QUICK_SAVE)
            ),
            new Entry(
                    "luma.hotkeys.undo",
                    "luma.hotkeys.undo_help",
                    List.of(LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.UNDO)
            ),
            new Entry(
                    "luma.hotkeys.redo",
                    "luma.hotkeys.redo_help",
                    List.of(LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.REDO)
            ),
            new Entry(
                    "luma.hotkeys.pending_preview",
                    "luma.hotkeys.pending_preview_help",
                    List.of(LumiClientKeyBindings.Role.ACTION)
            ),
            new Entry(
                    "luma.hotkeys.quick_rollback",
                    "luma.hotkeys.quick_rollback_help",
                    List.of(LumiClientKeyBindings.Role.QUICK_ROLLBACK)
            ),
            new Entry(
                    "luma.hotkeys.compare_overlay",
                    "luma.hotkeys.compare_overlay_help",
                    List.of(LumiClientKeyBindings.Role.COMPARE)
            ),
            new Entry(
                    "luma.hotkeys.shortcut_info",
                    "luma.hotkeys.shortcut_info_help",
                    List.of(LumiClientKeyBindings.Role.ACTION, LumiClientKeyBindings.Role.INFO)
            )
    );

    private LumiShortcutCatalog() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public record Entry(String labelKey, String helpKey, List<LumiClientKeyBindings.Role> roles) {

        public Entry {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
