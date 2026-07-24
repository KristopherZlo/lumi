package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Navigation and shared snapshot state for one open project window. */
final class LumiPageSession {
    private final ClientHistoryStore history;
    private final Map<ProjectTab, Consumer<Screen>> routes =
            new EnumMap<>(ProjectTab.class);
    private Screen historyPage;
    private boolean initialOpening = true;
    private boolean pageEntryPending;

    LumiPageSession(ClientHistoryStore history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    void attachHistory(Screen page) {
        historyPage = Objects.requireNonNull(page, "page");
    }

    void route(ProjectTab tab, Consumer<Screen> destination) {
        routes.put(Objects.requireNonNull(tab, "tab"),
                Objects.requireNonNull(destination, "destination"));
    }

    void open(ProjectTab tab) {
        if (historyPage == null) return;
        if (tab == ProjectTab.HISTORY) {
            pageEntryPending = true;
            Minecraft.getInstance().setScreen(historyPage);
            return;
        }
        Consumer<Screen> destination = routes.get(tab);
        if (destination != null) {
            pageEntryPending = true;
            destination.accept(historyPage);
        }
    }

    Optional<HistorySnapshotPayload> snapshot() {
        return history.state().snapshot();
    }

    boolean consumeInitialOpening() {
        boolean initial = initialOpening;
        initialOpening = false;
        return initial;
    }

    boolean consumePageEntry() {
        boolean pending = pageEntryPending;
        pageEntryPending = false;
        return pending;
    }
}
