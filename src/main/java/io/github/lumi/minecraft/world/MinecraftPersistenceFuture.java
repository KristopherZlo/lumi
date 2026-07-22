package io.github.lumi.minecraft.world;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Converts asynchronous vanilla persistence failures into the Restore I/O contract. */
final class MinecraftPersistenceFuture {
    private MinecraftPersistenceFuture() { }

    static <T> T join(CompletableFuture<T> future, String action) throws IOException {
        try {
            return future.join();
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(action + " failed", cause);
        }
    }
}
