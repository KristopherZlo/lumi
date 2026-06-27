package io.github.luma.ui.onboarding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OnboardingTourTest {

    @Test
    void firstTourUsesHandsOnPageOrder() {
        Assertions.assertEquals(List.of(
                "welcome",
                "break_block",
                "preview_changes",
                "undo_world",
                "redo_world",
                "save_shortcut",
                "open",
                "save_spotlight",
                "changes_spotlight",
                "commit_navigation",
                "finish"
        ), OnboardingTour.pageIds());
    }

    @Test
    void firstTourHasElevenPages() {
        Assertions.assertEquals(11, OnboardingTour.pageCount());
    }

    @Test
    void worldEditPageClosesWorkspaceUntilEnoughEditsAreSeen() {
        OnboardingTour tour = new OnboardingTour();

        Assertions.assertEquals(OnboardingTour.Transition.REBUILD, tour.next());
        Assertions.assertEquals("break_block", tour.currentPageId());
        Assertions.assertEquals(OnboardingTour.Transition.CLOSE_WORKSPACE, tour.next());
        Assertions.assertEquals("break_block", tour.currentPageId());

        Assertions.assertEquals(OnboardingTour.Transition.REBUILD, tour.advanceAfterWorldEdit());
        Assertions.assertEquals("preview_changes", tour.currentPageId());
    }

    @Test
    void previewPageClosesWorkspaceUntilActionHoldCompletes() {
        OnboardingTour tour = new OnboardingTour();
        tour.next();
        tour.advanceAfterWorldEdit();

        Assertions.assertEquals("preview_changes", tour.currentPageId());
        Assertions.assertEquals(OnboardingTour.Transition.CLOSE_WORKSPACE, tour.next());
        Assertions.assertEquals("preview_changes", tour.currentPageId());

        Assertions.assertEquals(OnboardingTour.Transition.REBUILD, tour.advanceAfterPendingPreview());
        Assertions.assertEquals("undo_world", tour.currentPageId());
    }

    @Test
    void quickSaveCompletionMovesToOpenWorkspaceShortcut() {
        OnboardingTour tour = new OnboardingTour();
        tour.next();
        tour.advanceAfterWorldEdit();
        tour.advanceAfterPendingPreview();
        tour.next();
        tour.next();

        Assertions.assertEquals("save_shortcut", tour.currentPageId());
        Assertions.assertEquals(OnboardingTour.Transition.REBUILD, tour.advanceAfterQuickSave());
        Assertions.assertEquals("open", tour.currentPageId());
    }

    @Test
    void commitNavigationHighlightsLatestSaveRestoreButton() {
        OnboardingTour tour = new OnboardingTour();
        tour.next();
        tour.advanceAfterWorldEdit();
        tour.advanceAfterPendingPreview();
        tour.next();
        tour.next();
        tour.advanceAfterQuickSave();
        tour.next();
        tour.next();
        tour.next();

        Assertions.assertEquals("commit_navigation", tour.currentPageId());
        Assertions.assertEquals(OnboardingTour.SpotlightTarget.LATEST_SAVE_RESTORE, tour.workspaceSpotlightTarget());
    }

    @Test
    void previewAndFinishPagesRenderRealShortcutRows() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/onboarding/OnboardingTour.java"));

        Assertions.assertTrue(source.contains("previewShortcutRow("));
        Assertions.assertTrue(source.contains("finishInfoRow("));
        Assertions.assertTrue(source.contains("LumiClientKeyBindings.Role.INFO"));
    }
}
