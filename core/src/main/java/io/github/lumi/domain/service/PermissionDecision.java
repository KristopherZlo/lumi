package io.github.lumi.domain.service;

/** Stable reason returned by the server-authoritative Lumi permission policy. */
public enum PermissionDecision {
    ALLOWED,
    OPERATOR_REQUIRED,
    SURVIVAL_OPT_IN_REQUIRED
}
