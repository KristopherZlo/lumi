package io.github.luma.minecraft.world;

import io.github.luma.domain.model.SectionChangeMask;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchSectionFrame;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Converts persisted domain changes into Minecraft-ready chunk apply batches.
 */
public final class WorldChangeBatchPreparer {

    private static final String PRIMED_TNT_ENTITY_TYPE = "minecraft:tnt";

    private final ConnectedBlockPlacementExpander connectedBlockPlacementExpander = new ConnectedBlockPlacementExpander();
    private final PistonMechanismPlacementExpander pistonMechanismPlacementExpander = new PistonMechanismPlacementExpander();
    private final SectionApplySafetyClassifier sectionApplySafetyClassifier = new SectionApplySafetyClassifier();
    private static final MechanismStatePolicy MECHANISM_STATE_POLICY = new MechanismStatePolicy();
    private static final FluidSensitiveBlockReplayPolicy FLUID_REPLAY_POLICY = new FluidSensitiveBlockReplayPolicy();
    private final Supplier<BlockStateDecoder> blockStateDecoderFactory;

    public WorldChangeBatchPreparer() {
        this(BlockStatePaletteDecoder::new);
    }

    WorldChangeBatchPreparer(BlockStateDecoder blockStateDecoder) {
        this(() -> blockStateDecoder);
    }

    private WorldChangeBatchPreparer(Supplier<BlockStateDecoder> blockStateDecoderFactory) {
        this.blockStateDecoderFactory = blockStateDecoderFactory;
    }

    public List<PreparedChunkBatch> prepareTargetStates(
            ServerLevel level,
            Map<BlockPoint, StatePayload> targetStates,
            PreparedBlockPlacement.ReplayHint replayHint
    ) throws IOException {
        if (targetStates == null || targetStates.isEmpty()) {
            return List.of();
        }
        BlockStateDecoder blockStateDecoder = this.blockStateDecoderFactory.get();
        List<ConnectedBlockPlacementExpander.ChangePlacement> placements = new ArrayList<>();
        for (Map.Entry<BlockPoint, StatePayload> entry : targetStates.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            BlockState targetState = blockStateDecoder.decode(level, entry.getValue().stateTag());
            PreparedBlockPlacement.ReplayHint mergedHint = PreparedBlockPlacement.ReplayHint.merge(
                    replayHint,
                    replayHintFor(null, targetState)
            );
            placements.add(new ConnectedBlockPlacementExpander.ChangePlacement(
                    new PreparedBlockPlacement(
                            entry.getKey().toBlockPos(),
                            targetState,
                            entry.getValue().blockEntityTag() == null
                                    ? null
                                    : entry.getValue().blockEntityTag().copy(),
                            mergedHint
                    ),
                    null
            ));
        }
        Map<ChunkPoint, List<PreparedBlockPlacement>> grouped = this.connectedBlockPlacementExpander.groupByChunk(
                this.expandMechanismChanges(placements)
        );
        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (Map.Entry<ChunkPoint, List<PreparedBlockPlacement>> entry : grouped.entrySet()) {
            batches.add(this.prepareDecodedChunk(entry.getKey(), entry.getValue(), EntityBatch.empty()));
        }
        return List.copyOf(batches);
    }

    public List<PreparedChunkBatch> prepare(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues,
            ProgressListener progressListener,
            EntityApplyMode entityApplyMode
    ) throws IOException {
        return this.prepareAnalyzed(
                level,
                changes,
                entityChanges,
                applyNewValues,
                progressListener,
                entityApplyMode
        ).batches();
    }

    public PreparedWorldChangeBatches prepareAnalyzed(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues,
            ProgressListener progressListener,
            EntityApplyMode entityApplyMode
    ) throws IOException {
        changes = changes == null ? List.of() : changes;
        entityChanges = entityChanges == null ? List.of() : entityChanges;
        progressListener = progressListener == null ? ProgressListener.NO_OP : progressListener;
        int total = changes.size() + entityChanges.size();
        int completed = 0;
        BlockStateDecoder blockStateDecoder = this.blockStateDecoderFactory.get();

        List<ConnectedBlockPlacementExpander.ChangePlacement> blockPlacements = new ArrayList<>();
        Map<ChunkPoint, List<StoredEntityChange>> groupedEntities = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            StatePayload source = applyNewValues ? change.oldValue() : change.newValue();
            StatePayload target = applyNewValues ? change.newValue() : change.oldValue();
            BlockPos pos = new BlockPos(change.pos().x(), change.pos().y(), change.pos().z());
            BlockState sourceState = blockStateDecoder.decode(level, source == null ? null : source.stateTag());
            BlockState targetState = blockStateDecoder.decode(level, target == null ? null : target.stateTag());
            blockPlacements.add(new ConnectedBlockPlacementExpander.ChangePlacement(
                    new PreparedBlockPlacement(
                            pos,
                            targetState,
                            target == null || target.blockEntityTag() == null ? null : target.blockEntityTag().copy(),
                            replayHintFor(sourceState, targetState)
                    ),
                    sourceState
            ));
            completed += 1;
            progressListener.onDecoded(completed, total);
        }
        MechanismReplayScope mechanismReplayScope = this.mechanismScope(blockPlacements);
        Map<ChunkPoint, List<PreparedBlockPlacement>> grouped = this.connectedBlockPlacementExpander.groupByChunk(
                this.expandMechanismChanges(blockPlacements)
        );
        for (StoredEntityChange change : entityChanges) {
            groupedEntities.computeIfAbsent(change.chunk(), ignored -> new ArrayList<>()).add(change);
            completed += 1;
            progressListener.onDecoded(completed, total);
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        chunks.addAll(grouped.keySet());
        chunks.addAll(groupedEntities.keySet());
        for (ChunkPoint chunk : chunks) {
            batches.add(this.prepareDecodedChunk(
                    chunk,
                    grouped.getOrDefault(chunk, List.of()),
                    this.toEntityBatch(groupedEntities.getOrDefault(chunk, List.of()), applyNewValues, entityApplyMode)
            ));
        }
        return new PreparedWorldChangeBatches(batches, mechanismReplayScope);
    }

    public List<PreparedChunkBatch> prepareUndoRedo(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues,
            ProgressListener progressListener,
            EntityApplyMode entityApplyMode
    ) throws IOException {
        changes = changes == null ? List.of() : changes;
        entityChanges = undoRedoReplayEntityChanges(entityChanges, applyNewValues);
        if (changes.size() < SectionApplySafetyClassifier.CONTAINER_REWRITE_THRESHOLD) {
            return this.prepare(level, changes, entityChanges, applyNewValues, progressListener, entityApplyMode);
        }

        BlockStateDecoder blockStateDecoder = this.blockStateDecoderFactory.get();
        PreparedWorldChangeBatches analyzed = this.prepareUndoRedoSectionFirst(
                level,
                changes,
                entityChanges,
                applyNewValues,
                progressListener == null ? ProgressListener.NO_OP : progressListener,
                blockStateDecoder,
                entityApplyMode
        );
        return analyzed == null
                ? this.prepare(level, changes, entityChanges, applyNewValues, progressListener, entityApplyMode)
                : analyzed.batches();
    }

    public PreparedWorldChangeBatches prepareUndoRedoAnalyzed(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues,
            ProgressListener progressListener,
            EntityApplyMode entityApplyMode
    ) throws IOException {
        changes = changes == null ? List.of() : changes;
        entityChanges = undoRedoReplayEntityChanges(entityChanges, applyNewValues);
        if (changes.size() < SectionApplySafetyClassifier.CONTAINER_REWRITE_THRESHOLD) {
            return this.prepareAnalyzed(level, changes, entityChanges, applyNewValues, progressListener, entityApplyMode);
        }

        BlockStateDecoder blockStateDecoder = this.blockStateDecoderFactory.get();
        PreparedWorldChangeBatches analyzed = this.prepareUndoRedoSectionFirst(
                level,
                changes,
                entityChanges,
                applyNewValues,
                progressListener == null ? ProgressListener.NO_OP : progressListener,
                blockStateDecoder,
                entityApplyMode
        );
        return analyzed == null
                ? this.prepareAnalyzed(level, changes, entityChanges, applyNewValues, progressListener, entityApplyMode)
                : analyzed;
    }

    public List<PreparedChunkBatch> prepare(
            ServerLevel level,
            PatchSectionWorldChanges changes,
            boolean applyNewValues,
            ProgressListener progressListener,
            EntityApplyMode entityApplyMode
    ) throws IOException {
        return this.prepareAnalyzed(level, changes, applyNewValues, progressListener, entityApplyMode).batches();
    }

    public PreparedWorldChangeBatches prepareAnalyzed(
            ServerLevel level,
            PatchSectionWorldChanges changes,
            boolean applyNewValues,
            ProgressListener progressListener,
            EntityApplyMode entityApplyMode
    ) throws IOException {
        if (changes == null || changes.sectionFrames().isEmpty()) {
            return this.prepareAnalyzed(
                    level,
                    List.of(),
                    changes == null ? List.of() : changes.entityChanges(),
                    applyNewValues,
                    progressListener,
                    entityApplyMode
            );
        }
        progressListener = progressListener == null ? ProgressListener.NO_OP : progressListener;
        int total = changes.sectionFrames().stream()
                .mapToInt(frame -> new SectionChangeMask(frame.changedMask()).cardinality())
                .sum() + changes.entityChanges().size();
        int[] completed = new int[] {0};

        Map<ChunkPoint, List<PreparedSectionApplyBatch>> nativeSections = new LinkedHashMap<>();
        Map<ChunkPoint, List<PreparedBlockPlacement>> sparsePlacements = new LinkedHashMap<>();
        MechanismReplayScope.Builder mechanismScopeBuilder = MechanismReplayScope.builder();
        BlockStateDecoder blockStateDecoder = this.blockStateDecoderFactory.get();
        for (PatchSectionFrame frame : changes.sectionFrames()) {
            ChunkPoint chunk = new ChunkPoint(frame.chunkX(), frame.chunkZ());
            DecodedSectionChanges decoded = this.decodeSectionChanges(
                    level,
                    frame,
                    applyNewValues,
                    completed,
                    total,
                    progressListener,
                    blockStateDecoder
            );
            mechanismScopeBuilder.addAll(this.mechanismScope(decoded.changes()));
            LumiSectionBuffer buffer = decoded.targetBuffer();
            List<PreparedBlockPlacement> mechanismCompanions = this.generatedMechanismCompanions(decoded.changes());
            boolean fullSection = buffer.changedCellCount() == SectionChangeMask.ENTRY_COUNT;
            SectionApplySafetyProfile profile = this.sectionApplySafetyClassifier.classify(buffer, fullSection);
            PreparedSectionApplyBatch nativeBatch = new PreparedSectionApplyBatch(
                    chunk,
                    frame.sectionY(),
                    buffer,
                    profile,
                    fullSection
            );
            if (profile.path() == SectionApplyPath.DIRECT_SECTION) {
                sparsePlacements.computeIfAbsent(chunk, ignored -> new ArrayList<>()).addAll(nativeBatch.toPlacements());
                this.addSparsePlacements(sparsePlacements, mechanismCompanions);
            } else {
                nativeSections.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(nativeBatch);
                this.addSparsePlacements(sparsePlacements, mechanismCompanions);
            }
        }

        Map<ChunkPoint, List<StoredEntityChange>> groupedEntities = new LinkedHashMap<>();
        for (StoredEntityChange change : changes.entityChanges()) {
            groupedEntities.computeIfAbsent(change.chunk(), ignored -> new ArrayList<>()).add(change);
            completed[0] += 1;
            progressListener.onDecoded(completed[0], total);
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        chunks.addAll(nativeSections.keySet());
        chunks.addAll(sparsePlacements.keySet());
        chunks.addAll(groupedEntities.keySet());
        for (ChunkPoint chunk : chunks) {
            SectionSplit split = this.splitSections(chunk, sparsePlacements.getOrDefault(chunk, List.of()));
            List<PreparedSectionApplyBatch> combinedNativeSections = new ArrayList<>(nativeSections.getOrDefault(chunk, List.of()));
            combinedNativeSections.addAll(split.nativeSections());
            batches.add(new PreparedChunkBatch(
                    chunk,
                    split.sparsePlacements(),
                    combinedNativeSections,
                    this.toEntityBatch(groupedEntities.getOrDefault(chunk, List.of()), applyNewValues, entityApplyMode)
            ));
        }
        return new PreparedWorldChangeBatches(batches, mechanismScopeBuilder.build());
    }

    private DecodedSectionChanges decodeSectionChanges(
            ServerLevel level,
            PatchSectionFrame frame,
            boolean applyNewValues,
            int[] completed,
            int total,
            ProgressListener progressListener,
            BlockStateDecoder blockStateDecoder
    ) throws IOException {
        LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(frame.sectionY());
        List<Integer> localIndexes = new ArrayList<>();
        new SectionChangeMask(frame.changedMask()).forEachSetCell(localIndexes::add);
        int[] sourceStateIds = applyNewValues ? frame.oldStateIds() : frame.newStateIds();
        int[] targetStateIds = applyNewValues ? frame.newStateIds() : frame.oldStateIds();
        int[] blockEntityIds = applyNewValues ? frame.newBlockEntityIds() : frame.oldBlockEntityIds();
        List<CompoundTag> sourceStatePalette = applyNewValues ? frame.oldStatePalette() : frame.newStatePalette();
        List<CompoundTag> targetStatePalette = applyNewValues ? frame.newStatePalette() : frame.oldStatePalette();
        List<CompoundTag> blockEntityPalette = applyNewValues ? frame.newBlockEntityPalette() : frame.oldBlockEntityPalette();
        BlockState[] decodedSourcePalette = this.decodePalette(level, sourceStatePalette, blockStateDecoder);
        BlockState[] decodedTargetPalette = this.decodePalette(level, targetStatePalette, blockStateDecoder);
        List<ConnectedBlockPlacementExpander.ChangePlacement> decodedChanges = new ArrayList<>();
        for (int index = 0; index < localIndexes.size(); index++) {
            int localIndex = localIndexes.get(index);
            BlockState sourceState = decodedSourcePalette[sourceStateIds[index]];
            BlockState targetState = decodedTargetPalette[targetStateIds[index]];
            CompoundTag blockEntityTag = this.blockEntityAt(blockEntityPalette, blockEntityIds[index]);
            PreparedBlockPlacement.ReplayHint replayHint = replayHintFor(sourceState, targetState);
            builder.set(
                    localIndex,
                    targetState,
                    blockEntityTag,
                    replayHint
            );
            decodedChanges.add(new ConnectedBlockPlacementExpander.ChangePlacement(
                    new PreparedBlockPlacement(
                            new BlockPos(
                                    (frame.chunkX() << 4) + SectionChangeMask.localX(localIndex),
                                    (frame.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                                    (frame.chunkZ() << 4) + SectionChangeMask.localZ(localIndex)
                            ),
                            targetState,
                            blockEntityTag,
                            replayHint
                    ),
                    sourceState
            ));
            completed[0] += 1;
            progressListener.onDecoded(completed[0], total);
        }
        return new DecodedSectionChanges(builder.build(), List.copyOf(decodedChanges));
    }

    static PreparedBlockPlacement.ReplayHint replayHintFor(BlockState sourceState, BlockState targetState) {
        boolean fluid = isFluidRelated(sourceState) || isFluidRelated(targetState);
        boolean mechanism = isMechanismRelated(sourceState) || isMechanismRelated(targetState);
        boolean forceFinalReplay = mechanism && (targetState == null || targetState.isAir());
        return PreparedBlockPlacement.ReplayHint.of(forceFinalReplay, fluid, mechanism);
    }

    private MechanismReplayScope mechanismScope(
            List<ConnectedBlockPlacementExpander.ChangePlacement> changes
    ) {
        MechanismReplayScope.Builder builder = MechanismReplayScope.builder();
        for (ConnectedBlockPlacementExpander.ChangePlacement change : changes == null
                ? List.<ConnectedBlockPlacementExpander.ChangePlacement>of()
                : changes) {
            if (change == null || change.placement() == null || change.placement().pos() == null) {
                continue;
            }
            BlockState sourceState = change.sourceState();
            BlockState targetState = change.placement().state();
            if (!isMechanismRelated(sourceState) && !isMechanismRelated(targetState)) {
                continue;
            }
            BlockPos pos = change.placement().pos();
            builder.addMechanismPosition(pos);
            builder.addSignalHalo(pos);
            MECHANISM_STATE_POLICY.attachedNeighbor(pos, sourceState).ifPresent(builder::addContextPosition);
            MECHANISM_STATE_POLICY.attachedNeighbor(pos, targetState).ifPresent(builder::addContextPosition);
        }
        return builder.build();
    }

    private static boolean isFluidRelated(BlockState state) {
        return state != null && (!state.getFluidState().isEmpty()
                || FLUID_REPLAY_POLICY.requiresFluidReplayGuard(state));
    }

    private static boolean isMechanismRelated(BlockState state) {
        return MECHANISM_STATE_POLICY.isMechanismReplayRelevant(state);
    }

    private BlockState[] decodePalette(
            ServerLevel level,
            List<CompoundTag> palette,
            BlockStateDecoder blockStateDecoder
    ) throws IOException {
        BlockState[] decoded = new BlockState[palette == null ? 0 : palette.size()];
        Map<CompoundTag, BlockState> frameCache = new LinkedHashMap<>();
        for (int index = 0; index < decoded.length; index++) {
            CompoundTag tag = palette.get(index);
            CompoundTag key = tag == null ? new CompoundTag() : tag.copy();
            BlockState state;
            if (frameCache.containsKey(key)) {
                state = frameCache.get(key);
            } else {
                state = blockStateDecoder.decode(level, tag);
                frameCache.put(key, state);
            }
            decoded[index] = state;
        }
        return decoded;
    }

    private CompoundTag blockEntityAt(List<CompoundTag> palette, int id) {
        return id < 0 ? null : palette.get(id).copy();
    }

    public PreparedChunkBatch prepareDecodedChunk(
            ChunkPoint chunk,
            List<PreparedBlockPlacement> placements,
            EntityBatch entityBatch
    ) {
        SectionSplit split = this.splitSections(chunk, placements);
        return new PreparedChunkBatch(
                chunk,
                split.sparsePlacements(),
                split.nativeSections(),
                entityBatch
        );
    }

    private SectionSplit splitSections(ChunkPoint chunk, List<PreparedBlockPlacement> placements) {
        if (placements == null || placements.isEmpty()) {
            return new SectionSplit(List.of(), List.of());
        }

        Map<Integer, List<PreparedBlockPlacement>> bySection = new LinkedHashMap<>();
        for (PreparedBlockPlacement placement : placements) {
            bySection.computeIfAbsent(Math.floorDiv(placement.pos().getY(), 16), ignored -> new ArrayList<>())
                    .add(placement);
        }

        List<PreparedBlockPlacement> sparse = new ArrayList<>();
        List<PreparedSectionApplyBatch> nativeSections = new ArrayList<>();
        bySection.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getValue().stream().anyMatch(placement -> placement.state() == null)) {
                        sparse.addAll(entry.getValue());
                        return;
                    }
                    LumiSectionBuffer buffer = this.toSectionBuffer(entry.getKey(), entry.getValue());
                    SectionApplySafetyProfile profile = this.sectionApplySafetyClassifier.classify(buffer, false);
                    if (profile.path() == SectionApplyPath.DIRECT_SECTION) {
                        sparse.addAll(entry.getValue());
                    } else {
                        nativeSections.add(new PreparedSectionApplyBatch(
                                chunk,
                                entry.getKey(),
                                buffer,
                                profile,
                                false
                        ));
                    }
                });
        return new SectionSplit(List.copyOf(sparse), List.copyOf(nativeSections));
    }

    private List<PreparedBlockPlacement> expandMechanismChanges(
            List<ConnectedBlockPlacementExpander.ChangePlacement> changes
    ) {
        List<PreparedBlockPlacement> connectedPlacements = this.connectedBlockPlacementExpander.expandChanges(changes);
        List<PistonMechanismPlacementExpander.ChangePlacement> pistonChanges = new ArrayList<>();
        for (ConnectedBlockPlacementExpander.ChangePlacement change : changes == null
                ? List.<ConnectedBlockPlacementExpander.ChangePlacement>of()
                : changes) {
            if (change == null) {
                continue;
            }
            pistonChanges.add(new PistonMechanismPlacementExpander.ChangePlacement(
                    change.placement(),
                    change.sourceState()
            ));
        }
        return this.mergePlacements(
                connectedPlacements,
                this.pistonMechanismPlacementExpander.expandChanges(pistonChanges)
        );
    }

    private List<PreparedBlockPlacement> mergePlacements(
            List<PreparedBlockPlacement> primary,
            List<PreparedBlockPlacement> secondary
    ) {
        LinkedHashMap<Long, PreparedBlockPlacement> merged = new LinkedHashMap<>();
        for (PreparedBlockPlacement placement : primary == null ? List.<PreparedBlockPlacement>of() : primary) {
            merged.put(packed(placement), placement);
        }
        for (PreparedBlockPlacement placement : secondary == null ? List.<PreparedBlockPlacement>of() : secondary) {
            long key = packed(placement);
            PreparedBlockPlacement existing = merged.get(key);
            if (existing == null || shouldOverrideTransientExplicit(existing, placement)) {
                merged.put(key, placement);
            }
        }
        return PistonMechanismPlacementExpander.ordered(merged.values());
    }

    private List<PreparedBlockPlacement> generatedMechanismCompanions(
            List<ConnectedBlockPlacementExpander.ChangePlacement> changes
    ) {
        LinkedHashMap<Long, PreparedBlockPlacement> explicit = new LinkedHashMap<>();
        for (ConnectedBlockPlacementExpander.ChangePlacement change : changes == null
                ? List.<ConnectedBlockPlacementExpander.ChangePlacement>of()
                : changes) {
            if (change == null || change.placement() == null) {
                continue;
            }
            explicit.put(packed(change.placement()), change.placement());
        }

        LinkedHashMap<Long, PreparedBlockPlacement> companions = new LinkedHashMap<>();
        this.collectGeneratedCompanions(
                companions,
                explicit,
                this.connectedBlockPlacementExpander.expandChanges(changes)
        );

        List<PistonMechanismPlacementExpander.ChangePlacement> pistonChanges = new ArrayList<>();
        for (ConnectedBlockPlacementExpander.ChangePlacement change : changes == null
                ? List.<ConnectedBlockPlacementExpander.ChangePlacement>of()
                : changes) {
            if (change == null) {
                continue;
            }
            pistonChanges.add(new PistonMechanismPlacementExpander.ChangePlacement(
                    change.placement(),
                    change.sourceState()
            ));
        }
        this.collectGeneratedCompanions(
                companions,
                explicit,
                this.pistonMechanismPlacementExpander.expandChanges(pistonChanges)
        );
        return PistonMechanismPlacementExpander.ordered(
                ConnectedBlockPlacementExpander.ordered(new ArrayList<>(companions.values()))
        );
    }

    private void collectGeneratedCompanions(
            LinkedHashMap<Long, PreparedBlockPlacement> companions,
            LinkedHashMap<Long, PreparedBlockPlacement> explicit,
            List<PreparedBlockPlacement> expanded
    ) {
        for (PreparedBlockPlacement placement : expanded == null ? List.<PreparedBlockPlacement>of() : expanded) {
            long key = packed(placement);
            PreparedBlockPlacement explicitPlacement = explicit.get(key);
            if (explicitPlacement == null || shouldOverrideTransientExplicit(explicitPlacement, placement)) {
                companions.put(key, placement);
            }
        }
    }

    private void addSparsePlacements(
            Map<ChunkPoint, List<PreparedBlockPlacement>> sparsePlacements,
            List<PreparedBlockPlacement> placements
    ) {
        for (PreparedBlockPlacement placement : placements == null ? List.<PreparedBlockPlacement>of() : placements) {
            sparsePlacements.computeIfAbsent(ChunkPoint.from(placement.pos()), ignored -> new ArrayList<>())
                    .add(placement);
        }
    }

    private PreparedWorldChangeBatches prepareUndoRedoSectionFirst(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues,
            ProgressListener progressListener,
            BlockStateDecoder blockStateDecoder,
            EntityApplyMode entityApplyMode
    ) throws IOException {
        entityChanges = entityChanges == null ? List.of() : entityChanges;
        int total = changes.size() + entityChanges.size();
        int completed = 0;
        Map<SectionKey, LumiSectionBuffer.Builder> sectionBuilders = new LinkedHashMap<>();
        MechanismReplayScope.Builder mechanismScope = MechanismReplayScope.builder();
        for (StoredBlockChange change : changes) {
            StatePayload source = applyNewValues ? change.oldValue() : change.newValue();
            StatePayload target = applyNewValues ? change.newValue() : change.oldValue();
            BlockState sourceState = blockStateDecoder.decode(level, source == null ? null : source.stateTag());
            BlockState targetState = blockStateDecoder.decode(level, target == null ? null : target.stateTag());
            if (this.connectedBlockPlacementExpander.requiresCompanion(sourceState)
                    || this.connectedBlockPlacementExpander.requiresCompanion(targetState)
                    || this.pistonMechanismPlacementExpander.requiresCompanion(sourceState)
                    || this.pistonMechanismPlacementExpander.requiresCompanion(targetState)) {
                return null;
            }

            SectionKey key = SectionKey.from(change);
            BlockPos pos = change.pos().toBlockPos();
            PreparedBlockPlacement.ReplayHint replayHint = replayHintFor(sourceState, targetState);
            sectionBuilders.computeIfAbsent(key, ignored -> LumiSectionBuffer.builder(key.sectionY()))
                    .set(
                            change.pos().x() & 15,
                            change.pos().y() & 15,
                            change.pos().z() & 15,
                            targetState,
                            target == null || target.blockEntityTag() == null ? null : target.blockEntityTag().copy(),
                            replayHint
                    );
            mechanismScope.addAll(this.mechanismScope(List.of(new ConnectedBlockPlacementExpander.ChangePlacement(
                    new PreparedBlockPlacement(pos, targetState, null, replayHint),
                    sourceState
            ))));
            completed += 1;
            progressListener.onDecoded(completed, total);
        }

        Map<ChunkPoint, List<PreparedSectionApplyBatch>> nativeSections = new LinkedHashMap<>();
        Map<ChunkPoint, List<PreparedBlockPlacement>> sparsePlacements = new LinkedHashMap<>();
        for (Map.Entry<SectionKey, LumiSectionBuffer.Builder> entry : sectionBuilders.entrySet()) {
            SectionKey key = entry.getKey();
            LumiSectionBuffer buffer = entry.getValue().build();
            boolean fullSection = buffer.changedCellCount() == SectionChangeMask.ENTRY_COUNT;
            SectionApplySafetyProfile profile = this.sectionApplySafetyClassifier.classify(buffer, fullSection);
            PreparedSectionApplyBatch nativeBatch = new PreparedSectionApplyBatch(
                    key.chunk(),
                    key.sectionY(),
                    buffer,
                    profile,
                    fullSection
            );
            if (profile.path() == SectionApplyPath.DIRECT_SECTION) {
                sparsePlacements.computeIfAbsent(key.chunk(), ignored -> new ArrayList<>()).addAll(nativeBatch.toPlacements());
            } else {
                nativeSections.computeIfAbsent(key.chunk(), ignored -> new ArrayList<>()).add(nativeBatch);
            }
        }

        Map<ChunkPoint, List<StoredEntityChange>> groupedEntities = new LinkedHashMap<>();
        for (StoredEntityChange change : entityChanges) {
            groupedEntities.computeIfAbsent(change.chunk(), ignored -> new ArrayList<>()).add(change);
            completed += 1;
            progressListener.onDecoded(completed, total);
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        chunks.addAll(nativeSections.keySet());
        chunks.addAll(sparsePlacements.keySet());
        chunks.addAll(groupedEntities.keySet());
        for (ChunkPoint chunk : chunks) {
            batches.add(new PreparedChunkBatch(
                    chunk,
                    sparsePlacements.getOrDefault(chunk, List.of()),
                    nativeSections.getOrDefault(chunk, List.of()),
                    this.toEntityBatch(groupedEntities.getOrDefault(chunk, List.of()), applyNewValues, entityApplyMode)
            ));
        }
        return new PreparedWorldChangeBatches(batches, mechanismScope.build());
    }

    private LumiSectionBuffer toSectionBuffer(int sectionY, List<PreparedBlockPlacement> placements) {
        LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(sectionY);
        for (PreparedBlockPlacement placement : placements) {
            BlockPos pos = placement.pos();
            builder.set(
                    pos.getX() & 15,
                    pos.getY() & 15,
                    pos.getZ() & 15,
                    placement.state(),
                    placement.blockEntityTag(),
                    placement.replayHint()
            );
        }
        return builder.build();
    }

    private static boolean shouldOverrideTransientExplicit(
            PreparedBlockPlacement existing,
            PreparedBlockPlacement replacement
    ) {
        return existing != null
                && replacement != null
                && existing.state() != null
                && (existing.state().isAir() || existing.state().is(Blocks.MOVING_PISTON))
                && replacement.state() != null
                && !replacement.state().isAir();
    }

    private static long packed(PreparedBlockPlacement placement) {
        return BlockPos.asLong(
                placement.pos().getX(),
                placement.pos().getY(),
                placement.pos().getZ()
        );
    }

    EntityBatch toEntityBatch(List<StoredEntityChange> changes, boolean applyNewValues) {
        return this.toEntityBatch(changes, applyNewValues, EntityApplyMode.DELTA);
    }

    EntityBatch toEntityBatch(
            List<StoredEntityChange> changes,
            boolean applyNewValues,
            EntityApplyMode entityApplyMode
    ) {
        List<CompoundTag> spawns = new ArrayList<>();
        List<String> removals = new ArrayList<>();
        List<CompoundTag> updates = new ArrayList<>();
        for (StoredEntityChange change : changes == null ? List.<StoredEntityChange>of() : changes) {
            StoredEntityChange target = applyNewValues ? change : change.inverse();
            if (target.isSpawn()) {
                spawns.add(target.newValue().copyTag());
            } else if (target.isRemove()) {
                removals.add(target.entityId());
            } else if (target.isUpdate()) {
                updates.add(target.newValue().copyTag());
            }
        }
        return new EntityBatch(
                spawns,
                removals,
                updates,
                entityApplyMode == EntityApplyMode.REPLACE_PLACED_IN_CHUNK
        );
    }

    private static List<StoredEntityChange> undoRedoReplayEntityChanges(
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues
    ) {
        entityChanges = entityChanges == null ? List.of() : entityChanges;
        if (!applyNewValues || entityChanges.isEmpty()) {
            return entityChanges;
        }
        return entityChanges.stream()
                .filter(change -> !isPrimedTntSpawn(change))
                .toList();
    }

    private static boolean isPrimedTntSpawn(StoredEntityChange change) {
        return change != null
                && change.isSpawn()
                && PRIMED_TNT_ENTITY_TYPE.equals(change.entityType());
    }

    @FunctionalInterface
    public interface ProgressListener {

        ProgressListener NO_OP = (completed, total) -> {
        };

        void onDecoded(int completed, int total);
    }

    private record SectionSplit(
            List<PreparedBlockPlacement> sparsePlacements,
            List<PreparedSectionApplyBatch> nativeSections
    ) {
    }

    private record DecodedSectionChanges(
            LumiSectionBuffer targetBuffer,
            List<ConnectedBlockPlacementExpander.ChangePlacement> changes
    ) {
    }

    private record SectionKey(ChunkPoint chunk, int sectionY) {

        private static SectionKey from(BlockPos pos) {
            return new SectionKey(ChunkPoint.from(pos), Math.floorDiv(pos.getY(), 16));
        }

        private static SectionKey from(StoredBlockChange change) {
            return new SectionKey(ChunkPoint.from(change.pos()), Math.floorDiv(change.pos().y(), 16));
        }
    }
}
