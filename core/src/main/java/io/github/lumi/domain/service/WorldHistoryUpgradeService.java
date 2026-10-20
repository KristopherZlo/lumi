package io.github.lumi.domain.service;

import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import io.github.lumi.storage.repository.HistoryGenerationRepository;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Preflights every dimension before one backup and worker-only history conversion. No live runtimes may exist. */
public final class WorldHistoryUpgradeService {
    private final DimensionRepositoryLayout layout;
    private final WorldDataVersion target;
    private final WorldPayloadConverter converter;
    private final Set<WorldDataVersion> permittedSources;

    public WorldHistoryUpgradeService(DimensionRepositoryLayout layout, WorldDataVersion target,
            WorldPayloadConverter converter, Set<WorldDataVersion> permittedSources) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.target = Objects.requireNonNull(target, "target");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.permittedSources = Set.copyOf(permittedSources);
    }

    public int upgrade(Backup backup, Consumer<HistoryGenerationConversionService.Progress> progress) throws IOException {
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(progress, "progress");
        var repositories = layout.existingRepositories();
        int conversions = 0;
        for (var repository : repositories) {
            var generations = new HistoryGenerationRepository(repository);
            var source = generations.active();
            WorldDataCompatibility.requireForward(source.dataVersion(), target);
            if (source.dataVersion().equals(target)) continue;
            if (!permittedSources.contains(source.dataVersion())) {
                throw new IOException("History upgrade route is not enabled: " + source.dataVersion().value()
                        + " -> " + target.value());
            }
            new HistoryGenerationConversionService(repository).requireRecovered(source);
            conversions++;
        }
        if (conversions > 0) backup.create();
        for (var repository : repositories) {
            var generations = new HistoryGenerationRepository(repository);
            if (!generations.active().dataVersion().equals(target) || generations.pending().isPresent()) {
                new HistoryGenerationConversionService(repository).convert(target, converter, progress);
            }
        }
        return conversions;
    }

    @FunctionalInterface
    public interface Backup {
        void create() throws IOException;
    }
}
