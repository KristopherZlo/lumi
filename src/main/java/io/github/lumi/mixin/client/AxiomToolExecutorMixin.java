package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.axiom.tools.Tool;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preserves the native single worker and thread factory while exposing actual completion. */
@Mixin(value = Tool.class, remap = false)
interface AxiomToolExecutorMixin {
    //? if >=26.1 {
    /*@WrapOperation(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/Executors;newSingleThreadExecutor(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;"))
    private static ExecutorService lumi$trackWorker(ThreadFactory factory, Operation<ExecutorService> original) {
        return AxiomClientWorkBarrier.singleThreadExecutor(factory);
    }
    *///?} else {
    @WrapOperation(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/Executors;newSingleThreadExecutor()Ljava/util/concurrent/ExecutorService;"))
    private static ExecutorService lumi$trackWorker(Operation<ExecutorService> original) {
        return AxiomClientWorkBarrier.singleThreadExecutor(Executors.defaultThreadFactory());
    }
    //?}
}
