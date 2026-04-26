package io.github.luma.ui.toolkit;

import java.util.List;

public final class OwoToolkitBackend implements UiToolkitBackend {

    @Override
    public UiToolkit toolkit() {
        return UiToolkit.OWO;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean primaryTarget() {
        return false;
    }

    @Override
    public List<String> notes() {
        return List.of("Fabric fallback used by the current 1.21.11 client build.");
    }
}
