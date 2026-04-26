package io.github.luma.ui.toolkit;

import java.util.List;

public interface UiToolkitBackend {

    UiToolkit toolkit();

    boolean available();

    boolean primaryTarget();

    List<String> notes();

    default String displayName() {
        return this.toolkit().displayName();
    }
}
