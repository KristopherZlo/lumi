package io.github.luma.ui.screen.section;

import java.util.List;

final class ProjectSaveCardLayout {

    private static final int WIDE_LAYOUT_MIN_WIDTH = 640;

    private ProjectSaveCardLayout() {
    }

    static Placement placementFor(int screenWidth) {
        return screenWidth >= WIDE_LAYOUT_MIN_WIDTH ? Placement.INLINE_RIGHT : Placement.STACKED_BELOW;
    }

    static List<ActionState> actions(boolean hasVersionVariant, boolean operationActive) {
        return actions(hasVersionVariant, operationActive, true);
    }

    static List<ActionState> actions(boolean hasVersionVariant, boolean operationActive, boolean createVariantAction) {
        return List.of(
                new ActionState(Action.RESTORE, hasVersionVariant && !operationActive),
                new ActionState(Action.CREATE_VARIANT, !operationActive, createVariantAction),
                new ActionState(Action.OPEN, true)
        ).stream().filter(ActionState::visible).toList();
    }

    enum Placement {
        INLINE_RIGHT,
        STACKED_BELOW
    }

    enum Action {
        OPEN,
        RESTORE,
        CREATE_VARIANT
    }

    record ActionState(Action action, boolean active, boolean visible) {

        ActionState(Action action, boolean active) {
            this(action, active, true);
        }
    }
}
