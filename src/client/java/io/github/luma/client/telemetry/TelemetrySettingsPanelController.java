package io.github.luma.client.telemetry;

import io.github.luma.telemetry.TelemetryService;
import io.github.luma.telemetry.TelemetrySettings;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TelemetrySettingsPanelController {

    private final Supplier<TelemetrySettings> settingsSupplier;
    private final Consumer<Boolean> enabledSetter;
    private final IntSupplier pendingCountSupplier;
    private final Supplier<String> lastSendSummarySupplier;
    private final Runnable clearQueueAction;

    public TelemetrySettingsPanelController() {
        this(
                TelemetryService.getInstance()::settings,
                TelemetryService.getInstance()::setEnabled,
                TelemetryService.getInstance()::pendingEventCount,
                TelemetryService.getInstance()::lastSendSummary,
                TelemetryService.getInstance()::clearLocalQueue
        );
    }

    TelemetrySettingsPanelController(
            Supplier<TelemetrySettings> settingsSupplier,
            Consumer<Boolean> enabledSetter,
            IntSupplier pendingCountSupplier,
            Supplier<String> lastSendSummarySupplier,
            Runnable clearQueueAction
    ) {
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
        this.enabledSetter = Objects.requireNonNull(enabledSetter, "enabledSetter");
        this.pendingCountSupplier = Objects.requireNonNull(pendingCountSupplier, "pendingCountSupplier");
        this.lastSendSummarySupplier = Objects.requireNonNull(lastSendSummarySupplier, "lastSendSummarySupplier");
        this.clearQueueAction = Objects.requireNonNull(clearQueueAction, "clearQueueAction");
    }

    public boolean enabled() {
        return this.settingsSupplier.get().enabled();
    }

    public String endpointUrl() {
        return this.settingsSupplier.get().endpointUrl();
    }

    public int pendingEventCount() {
        return this.pendingCountSupplier.getAsInt();
    }

    public String lastSendSummary() {
        return this.lastSendSummarySupplier.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabledSetter.accept(enabled);
    }

    public void clearLocalQueue() {
        this.clearQueueAction.run();
    }
}
