package io.github.luma.minecraft.capture;

import io.github.luma.debug.StartupProfiler;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ChunkSectionOwnershipRegistry {

    private static final ChunkSectionOwnershipRegistry INSTANCE = new ChunkSectionOwnershipRegistry();

    private final Map<LevelChunkSection, SectionOwner> owners = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<ChunkAccess, RegisteredSectionArray> registeredSectionArrays =
            Collections.synchronizedMap(new WeakHashMap<>());
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
                if (this.hasRegisteredSection(chunk, sections, index, sections[index])) {
                    continue;
                }
                this.register(levelChunk, serverLevel, chunkPos, index, sections[index], stats);
                registeredAnySection = true;
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

    public Optional<SectionOwner> ownerOf(LevelChunkSection section) {
        if (section == null) {
            return Optional.empty();
        }
        StartupStats stats = this.startupStats;
        long startedAt = stats == null ? 0L : System.nanoTime();
        try {
            if (stats != null) {
                stats.ownerLookupCalls.increment();
            }
            SectionOwner owner = this.owners.get(section);
            if (stats != null && owner != null) {
                stats.ownerLookupHits.increment();
            }
            return Optional.ofNullable(owner);
        } finally {
            if (stats != null) {
                stats.ownerLookupNanos.add(System.nanoTime() - startedAt);
            }
        }
    }

    public void logStartupProfile(String checkpoint) {
        StartupStats stats = this.startupStats;
        if (stats == null) {
            return;
        }
        stats.log(checkpoint, this.owners.size());
    }

    private boolean hasRegisteredSection(
            ChunkAccess chunk,
            LevelChunkSection[] sections,
            int index,
            LevelChunkSection section
    ) {
        synchronized (this.registeredSectionArrays) {
            RegisteredSectionArray registered = this.registeredSectionArrays.get(chunk);
            if (registered == null || !registered.matches(sections)) {
                registered = new RegisteredSectionArray(sections);
                this.registeredSectionArrays.put(chunk, registered);
            }
            if (registered.hasSection(index, section)) {
                return true;
            }
            registered.remember(index, section);
            return false;
        }
    }

    private void register(
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
                return;
            }

            int sectionY = levelChunk.getSectionYFromSectionIndex(sectionIndex);
            SectionOwner existing = this.owners.get(section);
            if (existing != null && existing.matches(serverLevel, chunkPos, sectionY)) {
                if (stats != null) {
                    stats.registerSectionNoops.increment();
                }
                return;
            }

            this.owners.put(section, new SectionOwner(serverLevel, chunkPos, sectionY));
            if (stats != null) {
                stats.registeredSections.increment();
            }
        } finally {
            if (stats != null) {
                stats.registerSectionNanos.add(System.nanoTime() - startedAt);
            }
        }
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
        private final LongAdder ownerLookupCalls = new LongAdder();
        private final LongAdder ownerLookupHits = new LongAdder();
        private final LongAdder ownerLookupNanos = new LongAdder();

        private void log(String checkpoint, int ownerCount) {
            long arrayCalls = this.registerArrayCalls.sum();
            long sectionCalls = this.registerSectionCalls.sum();
            long lookupCalls = this.ownerLookupCalls.sum();
            StartupProfiler.log(
                    "section-ownership checkpoint={} owners={} getSectionsCalls={} getSectionsCacheHits={} sectionEntries={} sectionRegisterCalls={} registeredSections={} sectionNoops={} registerTime={}us avgRegister={}ns ownerLookups={} ownerHits={} ownerLookupTime={}us avgOwnerLookup={}ns",
                    checkpoint,
                    ownerCount,
                    arrayCalls,
                    this.registerArrayCacheHits.sum(),
                    this.registerArrayEntries.sum(),
                    sectionCalls,
                    this.registeredSections.sum(),
                    this.registerSectionNoops.sum(),
                    this.registerSectionNanos.sum() / 1_000L,
                    averageNanos(this.registerSectionNanos.sum(), sectionCalls),
                    lookupCalls,
                    this.ownerLookupHits.sum(),
                    this.ownerLookupNanos.sum() / 1_000L,
                    averageNanos(this.ownerLookupNanos.sum(), lookupCalls)
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

    private static final class RegisteredSectionArray {

        private final LevelChunkSection[] sections;
        private final LevelChunkSection[] knownSections;

        private RegisteredSectionArray(LevelChunkSection[] sections) {
            this.sections = sections;
            this.knownSections = new LevelChunkSection[sections.length];
        }

        private boolean matches(LevelChunkSection[] sections) {
            return this.sections == sections && this.knownSections.length == sections.length;
        }

        private boolean hasSection(int index, LevelChunkSection section) {
            return index >= 0 && index < this.knownSections.length && this.knownSections[index] == section;
        }

        private void remember(int index, LevelChunkSection section) {
            if (index >= 0 && index < this.knownSections.length) {
                this.knownSections[index] = section;
            }
        }
    }
}
