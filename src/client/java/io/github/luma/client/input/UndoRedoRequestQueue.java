package io.github.luma.client.input;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded client-side intent queue for repeated undo/redo shortcut presses.
 */
final class UndoRedoRequestQueue {

    static final int MAX_REQUESTS = 16;

    private final Map<Scope, Deque<Intent>> intentsByScope = new LinkedHashMap<>();

    boolean offer(Scope scope, Intent intent) {
        if (scope == null || intent == null) {
            return false;
        }
        Deque<Intent> intents = this.intentsByScope.computeIfAbsent(scope, ignored -> new ArrayDeque<>());
        if (intents.size() >= MAX_REQUESTS) {
            return false;
        }
        intents.addLast(intent);
        return true;
    }

    Intent poll(Scope scope) {
        Deque<Intent> intents = this.intentsByScope.get(scope);
        if (intents == null) {
            return null;
        }
        Intent intent = intents.pollFirst();
        this.removeIfEmpty(scope, intents);
        return intent;
    }

    void offerFirst(Scope scope, Intent intent) {
        if (scope != null && intent != null) {
            this.intentsByScope.computeIfAbsent(scope, ignored -> new ArrayDeque<>()).addFirst(intent);
        }
    }

    boolean isEmpty(Scope scope) {
        Deque<Intent> intents = this.intentsByScope.get(scope);
        return intents == null || intents.isEmpty();
    }

    boolean hasAnyPending() {
        return !this.intentsByScope.isEmpty();
    }

    void retainOnly(Scope scope) {
        if (scope == null) {
            this.clear();
            return;
        }
        this.intentsByScope.keySet().removeIf(existing -> !existing.equals(scope));
    }

    int size(Scope scope) {
        Deque<Intent> intents = this.intentsByScope.get(scope);
        return intents == null ? 0 : intents.size();
    }

    void clear() {
        this.intentsByScope.clear();
    }

    private void removeIfEmpty(Scope scope, Deque<Intent> intents) {
        if (intents.isEmpty()) {
            this.intentsByScope.remove(scope);
        }
    }

    record Scope(String worldKey, String projectId) {

        Scope {
            worldKey = worldKey == null ? "" : worldKey;
            projectId = projectId == null ? "" : projectId;
        }
    }

    enum Intent {
        UNDO,
        REDO
    }
}
