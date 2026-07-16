package io.github.lumi.client.onboarding;

import java.util.List;

/** Small retained tour; world workflows remain available while it is replayed. */
public final class OnboardingTour {
    private static final List<Page> PAGES = List.of(
            page("welcome"),
            page("changes"),
            page("fix_mistakes"),
            page("safe_restore"),
            new Page("finish", "luma.onboarding.topic_finish",
                    "luma.onboarding.finish_help"));
    private int index;

    public static int pageCount() {
        return PAGES.size();
    }

    public Page current() {
        return PAGES.get(index);
    }

    public void next() {
        index = Math.min(PAGES.size() - 1, index + 1);
    }

    public void previous() {
        index = Math.max(0, index - 1);
    }

    public boolean first() {
        return index == 0;
    }

    public boolean last() {
        return index == PAGES.size() - 1;
    }

    public int displayIndex() {
        return index + 1;
    }

    private static Page page(String id) {
        return new Page(id, "luma.onboarding." + id + "_title",
                "luma.onboarding." + id + "_help");
    }

    public record Page(String id, String titleKey, String helpKey) { }
}
