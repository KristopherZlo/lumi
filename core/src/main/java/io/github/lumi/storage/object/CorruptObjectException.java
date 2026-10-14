package io.github.lumi.storage.object;

import java.io.IOException;

public final class CorruptObjectException extends IOException {
    public CorruptObjectException(String message) {
        super(message);
    }

    public CorruptObjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
