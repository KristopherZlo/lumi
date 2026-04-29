package io.github.luma.ui.state;

public record OnboardingFlowState(
        int pageIndex,
        int pageCount
) {

    public OnboardingFlowState {
        pageCount = Math.max(1, pageCount);
        pageIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
    }

    public static OnboardingFlowState first(int pageCount) {
        return new OnboardingFlowState(0, pageCount);
    }

    public OnboardingFlowState next() {
        return new OnboardingFlowState(this.pageIndex + 1, this.pageCount);
    }

    public OnboardingFlowState previous() {
        return new OnboardingFlowState(this.pageIndex - 1, this.pageCount);
    }

    public boolean firstPage() {
        return this.pageIndex == 0;
    }

    public boolean lastPage() {
        return this.pageIndex >= this.pageCount - 1;
    }

    public int displayIndex() {
        return this.pageIndex + 1;
    }
}
