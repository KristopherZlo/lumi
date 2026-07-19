package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.PackageName;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DimensionPackageServiceTest {
    @TempDir Path directory;

    @Test
    void refusesExportWhenTheSelectedBranchChangedBeforeBackgroundWork()
            throws Exception {
        Path repository = directory.resolve("repository");
        BranchRef current = new DimensionHistoryInitializer(
                new WorldObjectRepository(repository),
                new CommitRepository(repository),
                new BranchRefRepository(repository),
                new ActiveBranchRepository(repository))
                .initialize(new UUID(0, 1));
        BranchRef stale = new BranchRef(
                current.name(), current.commit(), current.revision() + 1);
        Path world = directory.resolve("world");
        var service = new DimensionPackageService(
                "minecraft:overworld", repository, world,
                Runnable::run, () -> current);

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> service.exportPackage(
                        new PackageName("clock"), stale, false).join());

        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(Files.notExists(world.resolve("lumi/packages/clock.lumi")));
    }
}
