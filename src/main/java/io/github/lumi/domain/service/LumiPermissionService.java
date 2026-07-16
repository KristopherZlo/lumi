package io.github.lumi.domain.service;

import java.io.IOException;
import java.util.Objects;

/** Applies operator and explicit Survival opt-in rules independently of Minecraft APIs. */
public final class LumiPermissionService {
    private final SurvivalOptInStore optIns;

    public LumiPermissionService(SurvivalOptInStore optIns) {
        this.optIns = Objects.requireNonNull(optIns, "optIns");
    }

    public PermissionDecision evaluate(PermissionSubject subject) throws IOException {
        Objects.requireNonNull(subject, "subject");
        if (!subject.operator()) {
            return PermissionDecision.OPERATOR_REQUIRED;
        }
        if (subject.survival() && !optIns.isEnabled(subject.playerId())) {
            return PermissionDecision.SURVIVAL_OPT_IN_REQUIRED;
        }
        return PermissionDecision.ALLOWED;
    }

    public void setSurvivalEnabled(PermissionSubject subject, boolean enabled)
            throws IOException {
        Objects.requireNonNull(subject, "subject");
        if (!subject.operator()) {
            throw new SecurityException("Only an operator can change Lumi Survival access");
        }
        optIns.setEnabled(subject.playerId(), enabled);
    }
}
