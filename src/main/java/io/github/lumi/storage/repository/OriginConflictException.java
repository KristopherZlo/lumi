package io.github.lumi.storage.repository;

import java.io.IOException;

public final class OriginConflictException extends IOException {
    public OriginConflictException(String message) {
        super(message);
    }
}
