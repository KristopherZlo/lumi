package io.github.luma.storage.repository;

import java.util.concurrent.locks.LockSupport;

final class BackgroundThrottle {

    private BackgroundThrottle() {
    }

    static void pauseEvery(int completedUnits, int interval, long nanos) {
        if (completedUnits <= 0 || interval <= 0 || nanos <= 0L) {
            return;
        }
        if ((completedUnits % interval) == 0) {
            LockSupport.parkNanos(nanos);
        }
    }
}
