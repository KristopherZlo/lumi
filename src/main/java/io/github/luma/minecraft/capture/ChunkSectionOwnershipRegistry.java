package io.github.luma.minecraft.capture;

import io.github.luma.debug.StartupProfiler;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ChunkSectionOwnershipRegistry implements ChunkSectionOwnerLookup {

    private static final ChunkSectionOwnershipRegistry INSTANCE = new ChunkSectionOwnershipRegistry();

    private final StartupStats startupStats = StartupProfiler.enabled() ? new StartupStats() : null;

    public static ChunkSectionOwnershipRegistry getInstance() {
        return INSTANCE;
    }

    public void register(ChunkAccess chunk, LevelChunkSection[] sections) {
        if (sections == null) {
            return;
        }
        if (!(chunk instanceof LevelChunk levelChunk) || !(levelChunk.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        StartupStats stats = this.startupStats;
        long startedAt = stats == null ? 0L : System.nanoTime();
        try {
            if (stats != null) {
                stats.registerArrayCalls.increment();
                stats.registerArrayEntries.add(sections.length);
            }
            ChunkPos chunkPos = chunk.getPos();
            boolean registeredAnySection = false;
            for (int index = 0; index < sections.length; index++) {
                registeredAnySection |= this.register(
                        levelChunk,
                        serverLevel,
                        chunkPos,
                        index,
                        sections[index],
                        stats
                );
            }
            if (stats != null && !registeredAnySection) {
                stats.registerArrayCacheHits.increment();
            }
        } finally {
            if (stats != null) {
                stats.registerArrayNanos.add(System.nanoTime() - startedAt);
            }
        }
    }

    public void register(ChunkAccess chunk, int sectionIndex, LevelChunkSection section) {
        StartupStats stats = this.startupStats;
        if (!(chunk instanceof LevelChunk levelChunk) || !(levelChunk.getLevel() instanceof ServerLevel serverLevel) || section == null) {
            return;
        }

        this.register(levelChunk, serverLevel, chunk.getPos(), sectionIndex, section, stats);
    }

    @Override
    public Optional<SectionOwner> ownerOf(LevelChunkSection section) {
        return Optional.ofNullable(section == null ? null : ownerAccess(section).luma$getOwner());
    }

    public void logStartupProfile(String checkpoint) {
        StartupStats stats = this.startupStats;
        if (stats == null) {
            return;
        }
        stats.log(checkpoint);
    }

    private boolean register(
            LevelChunk levelChunk,
            ServerLevel serverLevel,
            ChunkPos chunkPos,
            int sectionIndex,
            LevelChunkSection section,
            StartupStats stats
    ) {
        long startedAt = stats == null ? 0L : System.nanoTime();
        try {
            if (stats != null) {
                stats.registerSectionCalls.increment();
            }
            if (section == null) {
                return false;
            }

            int sectionY = levelChunk.getSectionYFromSectionIndex(sectionIndex);
            ChunkSectionOwnerAccess access = ownerAccess(section);
            SectionOwner existing = access.luma$getOwner();
            if (existing != null && existing.matches(serverLevel, chunkPos, sectionY)) {
                if (stats != null) {
                    stats.registerSectionNoops.increment();
                }
                return false;
            }

            SectionOwner owner = new SectionOwner(serverLevel, chunkPos, sectionY);
            access.luma$setOwner(owner);
            if (stats != null) {
                stats.registeredSections.increment();
            }
            return true;
        } finally {
            if (stats != null) {
                stats.registerSectionNanos.add(System.nanoTime() - startedAt);
            }
        }
    }

    private static ChunkSectionOwnerAccess ownerAccess(LevelChunkSection section) {
        return (ChunkSectionOwnerAccess) (Object) section;
    }

    public record SectionOwner(
            ServerLevel level,
            ChunkPos chunkPos,
            int sectionY
    ) {

        boolean matches(ServerLevel level, ChunkPos chunkPos, int sectionY) {
            return this.level == level && this.sectionY == sectionY && this.chunkPos.equals(chunkPos);
        }

        public BlockPos blockPos(int localX, int localY, int localZ) {
            return new BlockPos(
                    this.chunkPos.getBlockX(localX),
                    (this.sectionY << 4) + localY,
                    this.chunkPos.getBlockZ(localZ)
            );
        }
    }

    private static final class StartupStats {

        private final LongAdder registerArrayCalls = new LongAdder();
        private final LongAdder registerArrayEntries = new LongAdder();
        private final LongAdder registerArrayCacheHits = new LongAdder();
        private final LongAdder registerArrayNanos = new LongAdder();
        private final LongAdder registerSectionCalls = new LongAdder();
        private final LongAdder registeredSections = new LongAdder();
        private final LongAdder registerSectionNoops = new LongAdder();
        private final LongAdder registerSectionNanos = new LongAdder();

        private void log(String checkpoint) {
            long arrayCalls = this.registerArrayCalls.sum();
            long sectionCalls = this.registerSectionCalls.sum();
            long registered = this.registeredSections.sum();
            StartupProfiler.log(
                    "section-ownership checkpoint={} owners={} getSectionsCalls={} getSectionsCacheHits={} sectionEntries={} sectionRegisterCalls={} registeredSections={} sectionNoops={} registerTime={}us avgRegister={}ns",
                    checkpoint,
                    registered,
                    arrayCalls,
                    this.registerArrayCacheHits.sum(),
                    this.registerArrayEntries.sum(),
                    sectionCalls,
                    registered,
                    this.registerSectionNoops.sum(),
                    this.registerSectionNanos.sum() / 1_000L,
                    averageNanos(this.registerSectionNanos.sum(), sectionCalls)
            );
            StartupProfiler.log(
                    "section-ownership-array checkpoint={} getSectionsTime={}us avgGetSections={}ns",
                    checkpoint,
                    this.registerArrayNanos.sum() / 1_000L,
                    averageNanos(this.registerArrayNanos.sum(), arrayCalls)
            );
        }

        private static long averageNanos(long nanos, long count) {
            return count <= 0L ? 0L : nanos / count;
        }
    }
}
