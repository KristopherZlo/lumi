package io.github.luma.minecraft.world;

@FunctionalInterface
public interface CompletionAction {

    void run() throws Exception;
}
