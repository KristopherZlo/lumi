package io.github.luma.gbreak.server;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

final class GroundCorruptionBatchQueue<T> {

    private final Deque<T> pending = new ArrayDeque<>();
    private boolean planned;

    void load(Collection<T> candidates) {
        this.pending.clear();
        this.pending.addAll(candidates);
        this.planned = true;
    }

    void reset() {
        this.pending.clear();
        this.planned = false;
    }

    boolean isPlanned() {
        return this.planned;
    }

    boolean hasPending() {
        return !this.pending.isEmpty();
    }

    T removeFirst() {
        return this.pending.removeFirst();
    }
}
