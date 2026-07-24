package io.github.lumi.client.onboarding;

import java.util.List;
import java.util.Objects;

/** Page catalog and cursor for the short hands-on onboarding. */
public final class OnboardingTour {
    private static final List<Page> PAGES = List.of(
            page("welcome", Kind.INFO),
            page("break_block", Kind.WORLD_EDIT),
            page("preview_changes", Kind.WORLD_PREVIEW),
            page("undo_redo", Kind.WORLD_UNDO_REDO),
            page("save_shortcut", Kind.SHORTCUT_SAVE,
                    "key.lumi.action_modifier", "key.lumi.quick_save"),
            page("experiment", Kind.WORLD_EXPERIMENT),
            page("open", Kind.SHORTCUT_DASHBOARD,
                    "key.lumi.open_dashboard"),
            page("changes_spotlight", Kind.SPOTLIGHT_COMPARE),
            page("commit_navigation", Kind.SPOTLIGHT_RESTORE),
            page("finish", Kind.INFO_MORE,
                    "key.lumi.action_modifier", "key.lumi.hotkey_info"));
    private int index;

    public static int pageCount() {
        return PAGES.size();
    }

    public static List<String> pageIds() {
        return PAGES.stream().map(Page::id).toList();
    }

    public Page current() {
        return PAGES.get(index);
    }

    void moveNext() {
        index = Math.min(PAGES.size() - 1, index + 1);
    }

    void movePrevious() {
        if (canGoBack()) index--;
    }

    public boolean canGoBack() {
        return index > 0;
    }

    public int displayIndex() {
        return index + 1;
    }


    private static Page page(String id, Kind kind, String... bindings) {
        Objects.requireNonNull(id, "id");
        String prefix = "luma.onboarding." + id;
        return new Page(
                id, "luma.onboarding.topic_" + id,
                prefix + "_help", kind, List.of(bindings));
    }

    public enum Kind {
        INFO,
        WORLD_EDIT,
        WORLD_PREVIEW,
        WORLD_UNDO_REDO,
        SHORTCUT_SAVE,
        WORLD_EXPERIMENT,
        SHORTCUT_DASHBOARD,
        SPOTLIGHT_COMPARE,
        SPOTLIGHT_RESTORE,
        INFO_MORE
    }

    public record Page(
            String id,
            String titleKey,
            String helpKey,
            Kind kind,
            List<String> bindings) {
        public Page {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(titleKey, "titleKey");
            Objects.requireNonNull(helpKey, "helpKey");
            Objects.requireNonNull(kind, "kind");
            bindings = List.copyOf(bindings);
        }

        public boolean worldStep() {
            return kind == Kind.WORLD_EDIT
                    || kind == Kind.WORLD_PREVIEW
                    || kind == Kind.WORLD_UNDO_REDO
                    || kind == Kind.WORLD_EXPERIMENT;
        }

        public boolean spotlight() {
            return kind == Kind.SPOTLIGHT_COMPARE
                    || kind == Kind.SPOTLIGHT_RESTORE;
        }

        public boolean shortcutStep() {
            return !bindings.isEmpty();
        }
    }
}
