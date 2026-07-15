package io.github.lumi.storage.repository;

import java.io.IOException;

public final class RefConflictException extends IOException {
    public RefConflictException(String message) {
        super(message);
    }
}
