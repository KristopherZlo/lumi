package io.github.lumi.storage.object;

import io.github.lumi.domain.model.ActionReplayTarget;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldActionCheckpoint;
import io.github.lumi.domain.model.WorldActionContext;
import io.github.lumi.domain.model.WorldActionIndex;
import io.github.lumi.domain.model.WorldActionRecord;
import io.github.lumi.domain.model.WorldActionScope;
import io.github.lumi.domain.model.WorldDataVersion;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Small history root: cursors, checkpoint roots and immutable delta-file identities. */
public final class WorldActionIndexCodec {
    private static final int MAGIC = 0x4C414933;
    private static final int CONTEXT_MAGIC = 0x4C414932;
    private static final int PREVIOUS_MAGIC = 0x4C414931;
    private static final int MAX_ACTIONS = 16_384;
    private static final int MAX_SECTIONS = 32_768;
    private static final int MAX_SCOPE_ENTITIES = 262_144;

    public record State(UUID epoch, WorldDataVersion dataVersion, UUID workspace, BranchName branch,
            WorldActionIndex index, Map<UUID, UUID> payloads, Optional<Publication> publication) {
        public State {
            Objects.requireNonNull(epoch, "epoch");
            Objects.requireNonNull(dataVersion, "dataVersion");
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(publication, "publication");
            payloads = Map.copyOf(payloads);
            var required = new HashSet<UUID>();
            index.actions().stream().filter(action -> action.checkpoint().isEmpty()).forEach(action -> required.add(action.id()));
            if (!payloads.keySet().equals(required)) throw new IllegalArgumentException("Missing or extra action payload identities");
            if (new HashSet<>(payloads.values()).size() != payloads.size()) throw new IllegalArgumentException("Action payload identity reused across actions");
        }
    }

    public record Publication(UUID operation, ActionReplayTarget step, boolean applied) {
        public Publication {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(step, "step");
        }
    }

    public void encode(State state, DataOutputStream output) throws IOException {
        if (state.index().actions().size() > MAX_ACTIONS || state.index().players().size() > MAX_ACTIONS) {
            throw new IOException("Too many retained world actions or builders");
        }
        long sections = 0, entities = 0;
        for (var action : state.index().actions()) {
            if (action.checkpoint().isPresent()) {
                sections += action.checkpoint().orElseThrow().scope().sections().size();
                entities += action.checkpoint().orElseThrow().scope().entityIds().size();
            }
        }
        if (sections > MAX_SECTIONS || entities > MAX_SCOPE_ENTITIES) throw new IOException("Retained checkpoint scopes exceed the index budget");
        output.writeInt(MAGIC);
        CanonicalBytes.writeUuid(output, state.epoch());
        output.writeInt(state.dataVersion().value());
        CanonicalBytes.writeUuid(output, state.workspace());
        branch(output, state.branch());
        var index = state.index();
        output.writeLong(index.revision());
        output.writeLong(index.nextSequence());
        output.writeLong(index.unsafeBeforeSequence());
        output.writeInt(index.actions().size());
        for (var action : index.actions()) {
            CanonicalBytes.writeUuid(output, action.id());
            CanonicalBytes.writeUuid(output, action.actor());
            output.writeLong(action.sequence());
            CanonicalBytes.writeUuid(output, action.group());
            output.writeLong(action.revision());
            output.writeByte((action.applied() ? 1 : 0) | (action.closed() ? 2 : 0) | (action.pending() ? 4 : 0));
            output.writeBoolean(action.axiomEntry().isPresent());
            if (action.axiomEntry().isPresent()) CanonicalBytes.writeUuid(output, action.axiomEntry().orElseThrow());
            output.writeBoolean(action.checkpoint().isPresent());
            if (action.checkpoint().isPresent()) writeCheckpoint(output, action.checkpoint().orElseThrow());
            else CanonicalBytes.writeUuid(output, state.payloads().get(action.id()));
        }
        output.writeInt(index.players().size());
        for (var player : index.players().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            CanonicalBytes.writeUuid(output, player.getKey());
            writeIds(output, player.getValue().undo());
            writeIds(output, player.getValue().redo());
        }
        output.writeBoolean(state.publication().isPresent());
        if (state.publication().isPresent()) {
            var publication = state.publication().orElseThrow();
            CanonicalBytes.writeUuid(output, publication.operation());
            CanonicalBytes.writeUuid(output, publication.step().actor());
            CanonicalBytes.writeUuid(output, publication.step().action());
            output.writeByte((publication.step().undo() ? 1 : 0) | (publication.step().selective() ? 2 : 0));
            output.writeBoolean(publication.applied());
        }
    }

    public State decode(DataInputStream input) throws IOException {
        int magic = input.readInt();
        if (magic != MAGIC && magic != CONTEXT_MAGIC && magic != PREVIOUS_MAGIC) throw new IOException("Not a Lumi action index");
        try {
            UUID epoch = CanonicalBytes.readUuid(input);
            var version = new WorldDataVersion(input.readInt());
            UUID workspace = CanonicalBytes.readUuid(input);
            BranchName branch = branch(input);
            long revision = input.readLong(), sequence = input.readLong(), unsafe = input.readLong();
            int count = count(input, MAX_ACTIONS);
            var actions = new ArrayList<WorldActionRecord.Metadata>();
            var payloads = new HashMap<UUID, UUID>();
            var scopeBudget = new ScopeBudget();
            for (int index = 0; index < count; index++) {
                UUID id = CanonicalBytes.readUuid(input), actor = CanonicalBytes.readUuid(input);
                long order = input.readLong();
                UUID group = CanonicalBytes.readUuid(input);
                long contentRevision = input.readLong();
                int flags = input.readUnsignedByte();
                if ((flags & ~7) != 0) throw new IOException("Invalid action index flags");
                Optional<UUID> axiomEntry = magic == MAGIC && flag(input) ? Optional.of(CanonicalBytes.readUuid(input)) : Optional.empty();
                Optional<WorldActionCheckpoint> checkpoint = Optional.empty();
                if (flag(input)) checkpoint = Optional.of(readCheckpoint(input, scopeBudget, magic != PREVIOUS_MAGIC));
                else payloads.put(id, CanonicalBytes.readUuid(input));
                actions.add(new WorldActionRecord.Metadata(id, actor, order, group, contentRevision,
                        (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, checkpoint, axiomEntry));
            }
            int players = count(input, MAX_ACTIONS), remaining = actions.size();
            var stacks = new HashMap<UUID, WorldActionIndex.Stacks>();
            for (int index = 0; index < players; index++) {
                UUID actor = CanonicalBytes.readUuid(input);
                var undo = readIds(input, remaining);
                remaining -= undo.size();
                var redo = readIds(input, remaining);
                remaining -= redo.size();
                if (stacks.put(actor, new WorldActionIndex.Stacks(undo, redo)) != null) throw new IOException("Duplicate action actor");
            }
            Optional<Publication> publication = Optional.empty();
            if (flag(input)) publication = Optional.of(new Publication(CanonicalBytes.readUuid(input),
                    readReplayTarget(input), flag(input)));
            if (input.read() != -1) throw new IOException("Trailing action index bytes");
            return new State(epoch, version, workspace, branch,
                    new WorldActionIndex(revision, sequence, unsafe, actions, stacks), payloads, publication);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid action index", invalid);
        }
    }

    private static ActionReplayTarget readReplayTarget(DataInputStream input) throws IOException {
        UUID actor = CanonicalBytes.readUuid(input), action = CanonicalBytes.readUuid(input);
        int flags = input.readUnsignedByte();
        if (flags > 3) throw new IOException("Invalid action replay flags");
        return new ActionReplayTarget(actor, action, (flags & 1) != 0, (flags & 2) != 0);
    }

    private void writeCheckpoint(DataOutputStream output, WorldActionCheckpoint checkpoint) throws IOException {
        branch(output, checkpoint.branch());
        CanonicalBytes.writeId(output, checkpoint.before().value());
        CanonicalBytes.writeId(output, checkpoint.after().value());
        branch(output, checkpoint.retainedRef().name());
        CanonicalBytes.writeId(output, checkpoint.retainedRef().commit().value());
        output.writeLong(checkpoint.retainedRef().revision());
        var scope = checkpoint.scope();
        output.writeInt(scope.sections().size());
        for (var section : scope.sections().stream().sorted(Comparator.comparingInt(SectionKey::chunkX)
                .thenComparingInt(SectionKey::sectionY).thenComparingInt(SectionKey::chunkZ)).toList()) {
            output.writeInt(section.chunkX()); output.writeInt(section.sectionY()); output.writeInt(section.chunkZ());
            long[] words = scope.mask(section);
            output.writeByte(words.length);
            for (long word : words) output.writeLong(word);
        }
        writeIds(output, scope.entityIds().stream().sorted().toList());
        output.writeBoolean(checkpoint.context().isPresent());
        if (checkpoint.context().isPresent()) {
            var change = checkpoint.context().orElseThrow();
            context(output, change.before());
            context(output, change.after());
        }
    }

    private WorldActionCheckpoint readCheckpoint(DataInputStream input, ScopeBudget budget, boolean hasContext) throws IOException {
        BranchName branch = branch(input);
        var before = new CommitId(CanonicalBytes.readId(input));
        var after = new CommitId(CanonicalBytes.readId(input));
        var ref = new BranchRef(branch(input), new CommitId(CanonicalBytes.readId(input)), input.readLong());
        int sections = count(input, budget.sections);
        budget.sections -= sections;
        var masks = new HashMap<SectionKey, long[]>();
        for (int index = 0; index < sections; index++) {
            var key = new SectionKey(input.readInt(), input.readInt(), input.readInt());
            int length = input.readUnsignedByte();
            if (length < 1 || length > 64) throw new IOException("Invalid action section mask length");
            long[] words = new long[length];
            for (int word = 0; word < length; word++) words[word] = input.readLong();
            if (masks.put(key, words) != null) throw new IOException("Duplicate action section");
        }
        var ids = readIds(input, budget.entities);
        budget.entities -= ids.size();
        if (new HashSet<>(ids).size() != ids.size()) throw new IOException("Duplicate action scope entity");
        Optional<WorldActionContext.Change> change = hasContext && flag(input)
                ? Optional.of(new WorldActionContext.Change(context(input), context(input))) : Optional.empty();
        return new WorldActionCheckpoint(branch, before, after, ref, WorldActionScope.fromMasks(masks, new HashSet<>(ids)), change);
    }

    private void context(DataOutputStream output, WorldActionContext context) throws IOException {
        CanonicalBytes.writeUuid(output, context.workspace());
        branch(output, context.branch());
    }
    private WorldActionContext context(DataInputStream input) throws IOException {
        return new WorldActionContext(CanonicalBytes.readUuid(input), branch(input));
    }

    private void branch(DataOutputStream output, BranchName branch) throws IOException { CanonicalBytes.writeString(output, branch.value(), 1024, "action branch"); }
    private BranchName branch(DataInputStream input) throws IOException { return new BranchName(CanonicalBytes.readString(input, 1024, "action branch")); }
    private void writeIds(DataOutputStream output, List<UUID> ids) throws IOException {
        output.writeInt(ids.size());
        for (UUID id : ids) CanonicalBytes.writeUuid(output, id);
    }
    private List<UUID> readIds(DataInputStream input, int maximum) throws IOException {
        int count = count(input, maximum);
        var ids = new ArrayList<UUID>();
        for (int index = 0; index < count; index++) ids.add(CanonicalBytes.readUuid(input));
        return ids;
    }
    private int count(DataInputStream input, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IOException("Action index count exceeds its limit");
        return count;
    }
    private boolean flag(DataInputStream input) throws IOException {
        int flag = input.readUnsignedByte();
        if (flag > 1) throw new IOException("Invalid action index flag");
        return flag == 1;
    }

    private static final class ScopeBudget {
        private int sections = MAX_SECTIONS;
        private int entities = MAX_SCOPE_ENTITIES;
    }
}
