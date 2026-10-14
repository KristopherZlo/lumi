package io.github.lumi.storage.repository;

import java.io.IOException;

public final class JournalConflictException extends IOException {
    public JournalConflictException(String message) {
        super(message);
    }
}
