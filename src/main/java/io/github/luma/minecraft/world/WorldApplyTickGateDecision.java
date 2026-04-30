package io.github.luma.minecraft.world;

record WorldApplyTickGateDecision(boolean canStart, String reason) {

    static WorldApplyTickGateDecision allow() {
        return new WorldApplyTickGateDecision(true, "allowed");
    }

    static WorldApplyTickGateDecision stop(String reason) {
        return new WorldApplyTickGateDecision(false, reason == null || reason.isBlank() ? "stopped" : reason);
    }
}
