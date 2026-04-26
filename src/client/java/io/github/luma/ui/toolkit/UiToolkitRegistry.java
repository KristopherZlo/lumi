package io.github.luma.ui.toolkit;

import java.util.List;

public final class UiToolkitRegistry {

    private final List<UiToolkitBackend> backends;

    public UiToolkitRegistry(List<UiToolkitBackend> backends) {
        if (backends.isEmpty()) {
            throw new IllegalArgumentException("At least one UI toolkit backend is required.");
        }
        this.backends = List.copyOf(backends);
    }

    public static UiToolkitRegistry defaultRegistry() {
        ClassLoader classLoader = UiToolkitRegistry.class.getClassLoader();
        return new UiToolkitRegistry(List.of(
                new LdLib2ToolkitBackend(classLoader),
                new OwoToolkitBackend()
        ));
    }

    public UiToolkitStatus status() {
        UiToolkit target = this.backends.stream()
                .filter(UiToolkitBackend::primaryTarget)
                .map(UiToolkitBackend::toolkit)
                .findFirst()
                .orElse(UiToolkit.OWO);
        UiToolkit active = this.backends.stream()
                .filter(UiToolkitBackend::available)
                .map(UiToolkitBackend::toolkit)
                .findFirst()
                .orElseThrow();
        return new UiToolkitStatus(active, target, this.backends);
    }
}
