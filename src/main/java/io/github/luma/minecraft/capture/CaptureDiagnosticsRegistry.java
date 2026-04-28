package io.github.luma.minecraft.capture;

import java.util.HashMap;
import java.util.Map;

final class CaptureDiagnosticsRegistry {

    private final Map<String, CaptureSessionDiagnostics> sessionDiagnostics = new HashMap<>();

    CaptureSessionDiagnostics forSession(String projectId) {
        return this.sessionDiagnostics.computeIfAbsent(projectId, ignored -> new CaptureSessionDiagnostics());
    }

    void clear(String projectId) {
        this.sessionDiagnostics.remove(projectId);
    }
}
