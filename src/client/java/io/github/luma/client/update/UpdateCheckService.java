package io.github.luma.client.update;

import io.github.luma.LumaMod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

public final class UpdateCheckService implements UpdatePromptSource {

    private static final String PRIMARY_URL_PROPERTY = "lumi.update.primaryUrl";
    private static final String FALLBACK_URL_PROPERTY = "lumi.update.fallbackUrl";
    private static final String DISABLED_PROPERTY = "lumi.update.disabled";
    private static final String DEFAULT_PRIMARY_URL = "https://kristopherzlo.github.io/lumi/updates/lumi-fabric.json";
    private static final String DEFAULT_FALLBACK_URL =
            "https://raw.githubusercontent.com/KristopherZlo/lumi/main/updates/lumi-fabric.json";
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(12);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    private final UpdateSource source;
    private final UpdateCandidateSelector selector;
    private final UpdateStateRepository repository;
    private final Supplier<InstalledModInfo> installedInfo;
    private final Clock clock;
    private final Executor executor;
    private final Duration checkInterval;
    private final Set<String> snoozedVersions = ConcurrentHashMap.newKeySet();
    private volatile UpdateCheckState state;
    private CompletableFuture<UpdateCheckResult> inFlight;

    UpdateCheckService(
            UpdateSource source,
            UpdateCandidateSelector selector,
            UpdateStateRepository repository,
            Supplier<InstalledModInfo> installedInfo,
            Clock clock
    ) {
        this(source, selector, repository, installedInfo, clock, Runnable::run, DEFAULT_INTERVAL);
    }

    UpdateCheckService(
            UpdateSource source,
            UpdateCandidateSelector selector,
            UpdateStateRepository repository,
            Supplier<InstalledModInfo> installedInfo,
            Clock clock,
            Executor executor,
            Duration checkInterval
    ) {
        this.source = source;
        this.selector = selector;
        this.repository = repository;
        this.installedInfo = installedInfo;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.executor = executor == null ? Runnable::run : executor;
        this.checkInterval = checkInterval == null ? DEFAULT_INTERVAL : checkInterval;
        this.state = repository == null ? UpdateCheckState.empty() : repository.load();
    }

    public static UpdateCheckService getInstance() {
        return Holder.INSTANCE;
    }

    public Optional<UpdateRelease> promptRelease() {
        return this.state.promptRelease()
                .filter(release -> !this.snoozedVersions.contains(release.version()));
    }

    public synchronized void dismissVersion(String version) {
        this.state = this.state.withDismissedVersion(version);
        this.saveState(this.state);
    }

    public void snoozeVersion(String version) {
        if (version != null && !version.isBlank()) {
            this.snoozedVersions.add(version.trim());
        }
    }

    public synchronized CompletableFuture<UpdateCheckResult> requestCheckIfStale() {
        if (Boolean.getBoolean(DISABLED_PROPERTY)) {
            return CompletableFuture.completedFuture(UpdateCheckResult.unavailable("disabled"));
        }
        if (this.inFlight != null && !this.inFlight.isDone()) {
            return this.inFlight;
        }
        Instant now = this.clock.instant();
        if (!this.state.shouldCheck(now, this.checkInterval)) {
            return CompletableFuture.completedFuture(this.cachedResult());
        }

        this.inFlight = CompletableFuture.supplyAsync(this::checkNow, this.executor);
        return this.inFlight;
    }

    public synchronized CompletableFuture<UpdateCheckResult> requestCheckNow() {
        if (Boolean.getBoolean(DISABLED_PROPERTY)) {
            return CompletableFuture.completedFuture(UpdateCheckResult.unavailable("disabled"));
        }
        if (this.inFlight != null && !this.inFlight.isDone()) {
            return this.inFlight;
        }
        this.inFlight = CompletableFuture.supplyAsync(this::checkNow, this.executor);
        return this.inFlight;
    }

    UpdateCheckResult checkNow() {
        Instant checkedAt = this.clock.instant();
        try {
            SourcedUpdateManifest manifest = this.source.load();
            UpdateCheckResult result = this.selector.select(manifest.manifest(), this.installedInfo.get());
            this.store(this.state.withChecked(checkedAt, result));
            if (result.available()) {
                LumaMod.LOGGER.info(
                        "Lumi update available from {}: {}",
                        manifest.sourceName(),
                        result.release().version()
                );
            }
            return result;
        } catch (Exception exception) {
            LumaMod.LOGGER.debug("Lumi update check failed", exception);
            this.store(this.state.withFailedCheck(checkedAt));
            return UpdateCheckResult.unavailable(exception.getClass().getSimpleName());
        }
    }

    private UpdateCheckResult cachedResult() {
        return this.state.promptRelease()
                .map(UpdateCheckResult::available)
                .orElseGet(UpdateCheckResult::noneAvailable);
    }

    private synchronized void store(UpdateCheckState nextState) {
        this.state = nextState;
        this.saveState(nextState);
    }

    private void saveState(UpdateCheckState nextState) {
        if (this.repository != null) {
            this.repository.save(nextState);
        }
    }

    private static UpdateCheckService createDefault() {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "lumi-update-check");
            thread.setDaemon(true);
            return thread;
        });
        return new UpdateCheckService(
                defaultSource(),
                new UpdateCandidateSelector(),
                new UpdateCheckStateRepository(),
                UpdateCheckService::currentInstalledInfo,
                Clock.systemUTC(),
                executor,
                DEFAULT_INTERVAL
        );
    }

    private static UpdateSource defaultSource() {
        List<UpdateSource> sources = new ArrayList<>();
        String primaryUrl = System.getProperty(PRIMARY_URL_PROPERTY, DEFAULT_PRIMARY_URL);
        if (primaryUrl != null && !primaryUrl.isBlank()) {
            sources.add(new HttpUpdateSource("site", primaryUrl.trim(), HTTP_TIMEOUT));
        }
        String fallbackUrl = System.getProperty(FALLBACK_URL_PROPERTY, DEFAULT_FALLBACK_URL);
        if (fallbackUrl != null && !fallbackUrl.isBlank()) {
            sources.add(new HttpUpdateSource("github", fallbackUrl.trim(), HTTP_TIMEOUT));
        }
        return new UpdateSourceChain(sources);
    }

    private static InstalledModInfo currentInstalledInfo() {
        FabricLoader loader = FabricLoader.getInstance();
        String modVersion = loader.getModContainer(LumaMod.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        String minecraftVersion = loader.getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        return new InstalledModInfo(modVersion, minecraftVersion, "fabric");
    }

    private static final class Holder {

        private static final UpdateCheckService INSTANCE = createDefault();
    }
}
