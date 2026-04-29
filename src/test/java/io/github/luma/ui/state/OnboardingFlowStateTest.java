package io.github.luma.ui.state;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OnboardingFlowStateTest {

    @Test
    void nextAndPreviousStayWithinPageBounds() {
        OnboardingFlowState flow = OnboardingFlowState.first(5);

        Assertions.assertTrue(flow.firstPage());
        Assertions.assertFalse(flow.lastPage());

        flow = flow.previous();
        Assertions.assertEquals(0, flow.pageIndex());

        flow = flow.next().next().next().next().next();
        Assertions.assertEquals(4, flow.pageIndex());
        Assertions.assertTrue(flow.lastPage());

        flow = flow.next();
        Assertions.assertEquals(4, flow.pageIndex());
    }

    @Test
    void displayIndexIsOneBased() {
        OnboardingFlowState flow = new OnboardingFlowState(2, 5);

        Assertions.assertEquals(3, flow.displayIndex());
    }
}
