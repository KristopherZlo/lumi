package io.github.luma.ui.toolkit;

import java.util.List;

public final class MinecraftToolkitBackend implements UiToolkitBackend {

    @Override
    public UiToolkit toolkit() {
        return UiToolkit.MINECRAFT;
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
        return List.of("Packaged fallback renderer built on Minecraft client GUI primitives.");
    }
}
