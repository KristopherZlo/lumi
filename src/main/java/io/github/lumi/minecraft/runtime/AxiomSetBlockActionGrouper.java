package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.service.LiveActionJournal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Joins the packet fan-out produced by one Axiom Fast Place server tick. */
public final class AxiomSetBlockActionGrouper {
    private final LiveActionJournal journal;
    private final Map<UUID, Burst> latest = new HashMap<>();

    public AxiomSetBlockActionGrouper(LiveActionJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    public void join(UUID player, UUID action, long serverTick) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        if (!journal.owner(action).filter(player::equals).isPresent()) {
            return;
        }
        Burst previous = latest.put(player, new Burst(action, serverTick));
        if (previous != null
                && previous.serverTick == serverTick
                && journal.owner(previous.action).filter(player::equals).isPresent()) {
            journal.mergeGroups(previous.action, action);
        }
    }

    public void clear() {
        latest.clear();
    }

    private record Burst(UUID action, long serverTick) { }
}
