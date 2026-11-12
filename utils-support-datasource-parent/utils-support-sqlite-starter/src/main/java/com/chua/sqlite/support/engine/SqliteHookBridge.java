package com.chua.sqlite.support.engine;

import com.chua.common.support.nativeapi.NativeServices;
import com.chua.common.support.nativeapi.SqliteHookSpi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * SQLite CDC 原生钩子门面。
 *
 * <p>实现由 {@code utils-support-native-sqlite} 通过 SPI 注册；本类只依赖
 * {@link SqliteHookSpi} 契约，不再反射 native 类。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class SqliteHookBridge {

    private SqliteHookBridge() {
    }

    static boolean isAvailable() {
        SqliteHookSpi spi = NativeServices.get(SqliteHookSpi.class);
        return spi != null && spi.isAvailable();
    }

    static MethodHandle hookOpen() {
        return call(SqliteHookSpi::hookOpen);
    }

    static MethodHandle hookPoll() {
        return call(SqliteHookSpi::hookPoll);
    }

    static MethodHandle hookExec() {
        return call(SqliteHookSpi::hookExec);
    }

    static MethodHandle hookClose() {
        return call(SqliteHookSpi::hookClose);
    }

    static MethodHandle hookOpenAsync() {
        return call(SqliteHookSpi::hookOpenAsync);
    }

    static MethodHandle hookExecAsync() {
        return call(SqliteHookSpi::hookExecAsync);
    }

    static MethodHandle hookCloseAsync() {
        return call(SqliteHookSpi::hookCloseAsync);
    }

    static MemorySegment eventCallbackStub(MethodHandle target, Arena arena) {
        SqliteHookSpi spi = NativeServices.get(SqliteHookSpi.class);
        return spi == null ? MemorySegment.NULL : spi.eventCallbackStub(target, arena);
    }

    private static MethodHandle call(HandleAccessor accessor) {
        SqliteHookSpi spi = NativeServices.get(SqliteHookSpi.class);
        return spi == null ? null : accessor.get(spi);
    }

    /**
     * 句柄访问器。
     */
    @FunctionalInterface
    private interface HandleAccessor {

        /**
         * 从实现取句柄。
         *
         * @param spi 原生实现
         * @return 方法句柄
         */
        MethodHandle get(SqliteHookSpi spi);
    }
}
