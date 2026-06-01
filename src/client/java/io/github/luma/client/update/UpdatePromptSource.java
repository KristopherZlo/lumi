package io.github.luma.client.update;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

interface UpdatePromptSource {

    CompletableFuture<UpdateCheckResult> requestCheckIfStale();

    Optional<UpdateRelease> promptRelease();
}
