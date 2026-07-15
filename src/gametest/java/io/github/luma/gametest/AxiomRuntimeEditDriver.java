package io.github.luma.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Applies test mutations through Axiom's real server-side block-buffer method. */
final class AxiomRuntimeEditDriver {

    private static final String BUFFER_CLASS = "com.moulberry.axiom.world_modification.BlockBuffer";
    private static final String PACKET_CLASS = "com.moulberry.axiom.packets.AxiomServerboundSetBuffer";

    void apply(ServerLevel level, ServerPlayer player, Map<BlockPos, BlockState> changes) {
        try {
            Class<?> bufferType = Class.forName(BUFFER_CLASS);
            Object buffer = bufferType.getDeclaredConstructor().newInstance();
            Method set = this.requireMethod(bufferType, "set", 4, false);
            for (Map.Entry<BlockPos, BlockState> change : changes.entrySet()) {
                BlockPos pos = change.getKey();
                set.invoke(buffer, pos.getX(), pos.getY(), pos.getZ(), change.getValue());
            }

            Class<?> packetType = Class.forName(PACKET_CLASS);
            Method apply = this.requireMethod(packetType, "applyBlockBufferServer", 4, true);
            apply.invoke(null, buffer, level, null, player);
        } catch (InvocationTargetException exception) {
            throw this.runtimeFailure(exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Axiom runtime mutation path is unavailable", exception);
        }
    }

    private Method requireMethod(Class<?> owner, String name, int parameterCount, boolean staticMethod) {
        for (Method method : owner.getMethods()) {
            if (name.equals(method.getName())
                    && method.getParameterCount() == parameterCount
                    && Modifier.isStatic(method.getModifiers()) == staticMethod) {
                return method;
            }
        }
        throw new IllegalStateException("Missing Axiom method " + owner.getName() + "." + name);
    }

    private IllegalStateException runtimeFailure(Throwable cause) {
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Axiom runtime mutation failed", cause);
    }
}
