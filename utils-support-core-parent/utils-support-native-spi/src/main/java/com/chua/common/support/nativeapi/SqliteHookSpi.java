package com.chua.common.support.nativeapi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * SQLite update_hook 原生绑定 SPI。
 *
 * <p>由 {@code utils-support-native-sqlite} 提供实现。接口直接暴露 FFM 的
 * {@link MethodHandle} / {@link MemorySegment}，调用方（sqlite starter）负责
 * 同步连接语义与 Reactor 事件流语义。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface SqliteHookSpi extends NativeService {

    /**
     * 原生库是否可用。
     *
     * @return 可用返回 {@code true}
     */
    boolean isAvailable();

    @Override
    default boolean available() {
        return isAvailable();
    }

    /**
     * {@code hook_open} 句柄。
     *
     * @return 方法句柄
     */
    MethodHandle hookOpen();

    /**
     * {@code hook_poll} 句柄。
     *
     * @return 方法句柄
     */
    MethodHandle hookPoll();

    /**
     * {@code hook_exec} 句柄。
     *
     * @return 方法句柄
     */
    MethodHandle hookExec();

    /**
     * {@code hook_close} 句柄。
     *
     * @return 方法句柄
     */
    MethodHandle hookClose();

    /**
     * {@code hook_open_async} 句柄。
     *
     * @return 方法句柄
     */
    MethodHandle hookOpenAsync();

    /**
     * {@code hook_exec_async} 句柄。
     *
     * @return 方法句柄
     */
    MethodHandle hookExecAsync();

    /**
     * {@code hook_close_async} 句柄。
     *
     * @return 方法句柄
     */
    MethodHandle hookCloseAsync();

    /**
     * 为异步事件回调创建 upcall stub。
     *
     * @param target Java 侧目标方法
     * @param arena  承载 stub 生命周期的 Arena
     * @return 可传给原生层的函数指针
     */
    MemorySegment eventCallbackStub(MethodHandle target, Arena arena);
}
