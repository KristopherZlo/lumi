package io.github.luma.ui.toolkit;

import java.util.List;

public record UiToolkitStatus(
        UiToolkit activeToolkit,
        UiToolkit targetToolkit,
        List<UiToolkitBackend> backends
) {

    public UiToolkitStatus {
        backends = List.copyOf(backends);
    }

    public boolean targetActive() {
        return this.activeToolkit == this.targetToolkit;
    }

    public UiToolkitBackend activeBackend() {
        return this.backends.stream()
                .filter(backend -> backend.toolkit() == this.activeToolkit)
                .findFirst()
                .orElseThrow();
    }
}
