package io.github.lumi.client.onboarding;

import java.util.List;
import java.util.Objects;

/** Retained state for the nine-step hands-on legacy onboarding flow. */
public final class OnboardingTour {
    private static final List<Page> PAGES = List.of(
            page("welcome", Kind.INFO),
            page("break_block", Kind.WORLD_EDIT),
            page("preview_changes", Kind.WORLD_PREVIEW),
            page("save_shortcut", Kind.HOLD_SAVE,
                    "key.lumi.action_modifier", "key.lumi.quick_save"),
            page("open", Kind.HOLD_DASHBOARD,
                    "key.lumi.action_modifier", "key.lumi.open_dashboard"),
            page("save_spotlight", Kind.SPOTLIGHT_SAVE),
            page("changes_spotlight", Kind.SPOTLIGHT_CHANGES),
            page("commit_navigation", Kind.SPOTLIGHT_RESTORE),
            page("finish", Kind.HOLD_HOTKEYS,
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

    public void next() {
        index = Math.min(PAGES.size() - 1, index + 1);
    }

    public void previous() {
        if (canGoBack()) index--;
    }

    public boolean advanceWorldEdit() {
        return advance(Kind.WORLD_EDIT);
    }

    public boolean advancePendingPreview() {
        return advance(Kind.WORLD_PREVIEW);
    }

    public boolean advanceQuickSave() {
        return advance(Kind.HOLD_SAVE);
    }

    public boolean first() {
        return index == 0;
    }

    public boolean last() {
        return index == PAGES.size() - 1;
    }

    public boolean canGoBack() {
        return index > 0 && current().kind() != Kind.SPOTLIGHT_SAVE;
    }

    public int displayIndex() {
        return index + 1;
    }

    private boolean advance(Kind expected) {
        if (current().kind() != expected) return false;
        next();
        return true;
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
        HOLD_SAVE,
        HOLD_DASHBOARD,
        SPOTLIGHT_SAVE,
        SPOTLIGHT_CHANGES,
        SPOTLIGHT_RESTORE,
        HOLD_HOTKEYS
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
            return kind == Kind.WORLD_EDIT || kind == Kind.WORLD_PREVIEW;
        }

        public boolean spotlight() {
            return kind == Kind.SPOTLIGHT_SAVE
                    || kind == Kind.SPOTLIGHT_CHANGES
                    || kind == Kind.SPOTLIGHT_RESTORE;
        }

        public boolean holdStep() {
            return !bindings.isEmpty();
        }
    }
}
