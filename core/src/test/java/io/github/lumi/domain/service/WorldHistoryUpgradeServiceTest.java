package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lumi.domain.model.HistoryGeneration;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import io.github.lumi.storage.repository.HistoryGenerationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldHistoryUpgradeServiceTest {
    @TempDir Path world;
    private final WorldDataVersion modern = new WorldDataVersion(4786);

    @Test void upgradesEveryDimensionAfterOneSuccessfulBackupAndPreservesItsSource() throws Exception {
        var layout = new DimensionRepositoryLayout(world);
        var first = layout.resolve("minecraft:overworld");
        var second = layout.resolve("builder:custom");
        var original = HistoryGenerationConversionServiceTest.fixture(first);
        HistoryGenerationConversionServiceTest.fixture(second);
        var backups = new AtomicInteger();
        assertEquals(2, service(layout, Set.of(WorldDataVersion.LEGACY_12111)).upgrade(() -> {
            assertEquals(HistoryGeneration.LEGACY, new HistoryGenerationRepository(first).active());
            assertEquals(HistoryGeneration.LEGACY, new HistoryGenerationRepository(second).active());
            backups.incrementAndGet();
        }, ignored -> { }));
        assertEquals(1, backups.get());
        assertEquals(modern, new HistoryGenerationRepository(first).active().dataVersion());
        assertEquals(modern, new HistoryGenerationRepository(second).active().dataVersion());
        assertEquals(original.main(), new io.github.lumi.storage.repository.BranchRefRepository(first)
                .read(original.main().name()).orElseThrow());
        assertEquals(0, service(layout, Set.of()).upgrade(() -> fail("Repeated upgrade created a backup"), ignored -> { }));
    }

    @Test void failedBackupCannotPublishAnyDimension() throws Exception {
        var layout = new DimensionRepositoryLayout(world);
        var root = layout.resolve("minecraft:overworld");
        HistoryGenerationConversionServiceTest.fixture(root);
        assertThrows(IOException.class, () -> service(layout, Set.of(WorldDataVersion.LEGACY_12111))
                .upgrade(() -> { throw new IOException("Backup unavailable"); }, ignored -> { }));
        assertEquals(HistoryGeneration.LEGACY, new HistoryGenerationRepository(root).active());
        assertFalse(Files.exists(root.resolve("generations")));
    }

    @Test void disabledRouteCannotCreateBackupOrCandidate() throws Exception {
        var layout = new DimensionRepositoryLayout(world);
        var root = layout.resolve("minecraft:overworld");
        HistoryGenerationConversionServiceTest.fixture(root);
        assertThrows(IOException.class, () -> service(layout, Set.of())
                .upgrade(() -> fail("Unsupported route created a backup"), ignored -> { }));
        assertFalse(Files.exists(root.resolve("generations")));
    }

    @Test void refusesInvalidDimensionInventoryBeforeConversion() throws Exception {
        var layout = new DimensionRepositoryLayout(world);
        HistoryGenerationConversionServiceTest.fixture(layout.resolve("minecraft:overworld"));
        Files.writeString(layout.resolve("minecraft:overworld").getParent().resolve("unexpected"), "invalid");
        assertThrows(IOException.class, () -> service(layout, Set.of(WorldDataVersion.LEGACY_12111))
                .upgrade(() -> fail("Invalid inventory created a backup"), ignored -> { }));
        assertEquals(HistoryGeneration.LEGACY,
                new HistoryGenerationRepository(layout.resolve("minecraft:overworld")).active());
    }

    @Test void newWorldNeedsNeitherBackupNorConversion() throws Exception {
        var layout = new DimensionRepositoryLayout(world);
        assertEquals(0, service(layout, Set.of()).upgrade(() -> fail("Empty world was backed up"), ignored -> { }));
        assertFalse(Files.exists(world.resolve("lumi")));
    }

    @Test void interruptedOtherDimensionBlocksEveryUpgradeBeforeBackup() throws Exception {
        var layout = new DimensionRepositoryLayout(world);
        var first = layout.resolve("minecraft:overworld");
        HistoryGenerationConversionServiceTest.fixture(first);
        var second = layout.resolve("minecraft:the_nether");
        var ref = HistoryGenerationConversionServiceTest.fixture(second).main();
        var target = new io.github.lumi.domain.model.OperationTarget(ref.name(), ref.commit(), ref.revision(),
                java.util.Optional.of(ref.commit()), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), false);
        new io.github.lumi.storage.repository.OperationJournalRepository(second).create(
                new io.github.lumi.domain.model.OperationJournal(java.util.UUID.randomUUID(),
                        io.github.lumi.domain.model.OperationKind.RESTORE,
                        io.github.lumi.domain.model.OperationPhase.APPLYING, target));
        assertThrows(IOException.class, () -> service(layout, Set.of(WorldDataVersion.LEGACY_12111))
                .upgrade(() -> fail("Interrupted operation created a backup"), ignored -> { }));
        assertFalse(Files.exists(first.resolve("generations")));
        assertFalse(Files.exists(second.resolve("generations")));
    }

    private WorldHistoryUpgradeService service(DimensionRepositoryLayout layout, Set<WorldDataVersion> permitted) {
        return new WorldHistoryUpgradeService(layout, modern,
                HistoryGenerationConversionServiceTest.adapter(), permitted);
    }

    @Test void serverPermissionMetadataIsNotADimensionAndRemainsIntact() throws Exception {
        var permissions = new io.github.lumi.storage.repository.SurvivalOptInRepository(world);
        var player = java.util.UUID.randomUUID();
        permissions.setEnabled(player, true);
        assertEquals(0, service(new DimensionRepositoryLayout(world), Set.of())
                .upgrade(() -> fail("Permission settings triggered an upgrade"), ignored -> { }));
        assertTrue(permissions.isEnabled(player));
    }
}
