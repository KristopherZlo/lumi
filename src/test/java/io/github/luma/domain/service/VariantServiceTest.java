package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void createVariantDoesNotFreezeOrConsumePendingDraft() throws IOException {
        ProjectLayout layout = this.prepareProjectLayout();
        BuildProject project = new ProjectRepository().load(layout).orElseThrow();
        RecoveryRepository recoveryRepository = new RecoveryRepository();
        recoveryRepository.saveDraft(layout, draft(project));

        FakeCaptureSessionLifecycle captureSessionLifecycle = new FakeCaptureSessionLifecycle();
        VariantService service = new VariantService((server, projectName) -> layout, captureSessionLifecycle);

        ProjectVariant variant = service.createVariant(null, "Tower", "Feature branch", "");

        assertEquals("feature-branch", variant.id());
        assertEquals("v0001", variant.baseVersionId());
        assertEquals(0, captureSessionLifecycle.finalizeCalls);
        assertEquals(1, captureSessionLifecycle.invalidateCalls);
        assertTrue(recoveryRepository.loadDraft(layout).isPresent());
        assertEquals(
                List.of("main", "feature-branch"),
                new VariantRepository().loadAll(layout).stream().map(ProjectVariant::id).toList()
        );
    }

    @Test
    void createVariantGeneratesUniqueIdsForDifferentNamesWithFallbackSlugs() throws IOException {
        ProjectLayout layout = this.prepareProjectLayout();
        FakeCaptureSessionLifecycle captureSessionLifecycle = new FakeCaptureSessionLifecycle();
        VariantService service = new VariantService((server, projectName) -> layout, captureSessionLifecycle);

        ProjectVariant first = service.createVariant(null, "Tower", "Крыша", "");
        ProjectVariant second = service.createVariant(null, "Tower", "Фундамент", "");

        assertEquals("variant", first.id());
        assertEquals("variant-2", second.id());
        assertEquals("Крыша", first.name());
        assertEquals("Фундамент", second.name());
        assertEquals(
                List.of("main", "variant", "variant-2"),
                new VariantRepository().loadAll(layout).stream().map(ProjectVariant::id).toList()
        );
    }

    private ProjectLayout prepareProjectLayout() throws IOException {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Tower");
        BuildProject project = BuildProject.create(
                "Tower",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                NOW
        );
        new ProjectRepository().save(layout, project);
        new VersionRepository().save(layout, version(project));
        new VariantRepository().save(layout, List.of(ProjectVariant.main("v0001", NOW)));
        return layout;
    }

    private static ProjectVersion version(BuildProject project) {
        return new ProjectVersion(
                "v0001",
                project.id().toString(),
                "main",
                "",
                "",
                List.of(),
                VersionKind.MANUAL,
                "tester",
                "Initial save",
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW
        );
    }

    private static RecoveryDraft draft(BuildProject project) {
        return new RecoveryDraft(
                project.id().toString(),
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(new StoredBlockChange(
                        new BlockPoint(1, 65, 1),
                        StatePayload.air(),
                        new StatePayload(blockStateTag("minecraft:stone"), null)
                ))
        );
    }

    private static CompoundTag blockStateTag(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private static final class FakeCaptureSessionLifecycle implements VariantService.CaptureSessionLifecycle {

        private int finalizeCalls;
        private int invalidateCalls;

        @Override
        public void finalizeProjectSession(MinecraftServer server, String projectId) {
            this.finalizeCalls += 1;
        }

        @Override
        public void invalidateProjectCache(MinecraftServer server) {
            this.invalidateCalls += 1;
        }
    }
}
