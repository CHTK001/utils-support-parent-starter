package com.chua.metrics.support;

import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
public class MetricsNativeLibrary implements AutoCloseable {

    private static final SymbolLookup LOADER_LOOKUP;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle START_SAMPLER;
    private static final MethodHandle STOP_SAMPLER;
    private static final MethodHandle SNAPSHOT_SIZE;
    private static final MethodHandle GET_SNAPSHOT;

    static {
        try {
            Path target = Paths.get(System.getProperty("java.io.tmpdir"), "metrics-native");
            NativeLoader.of("metrics_native").toTarget(target).load();
            LOADER_LOOKUP = SymbolLookup.loaderLookup();
            START_SAMPLER = LINKER.downcallHandle(
                    LOADER_LOOKUP.find("metrics_start_sampler").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)
            );
            STOP_SAMPLER = LINKER.downcallHandle(
                    LOADER_LOOKUP.find("metrics_stop_sampler").orElseThrow(),
                    FunctionDescriptor.ofVoid()
            );
            SNAPSHOT_SIZE = LINKER.downcallHandle(
                    LOADER_LOOKUP.find("metrics_snapshot_size").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            GET_SNAPSHOT = LINKER.downcallHandle(
                    LOADER_LOOKUP.find("metrics_get_snapshot").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile boolean started;

    public static MetricsNativeLibrary create() {
        try {
            return new MetricsNativeLibrary();
        } catch (Exception e) {
            log.error("无法加载 metrics_native native 库", e);
            return null;
        }
    }

    private MetricsNativeLibrary() {
        this.started = false;
    }

    public void start(long intervalMs) {
        if (started) {
            return;
        }
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs 必须大于 0");
        }
        try {
            START_SAMPLER.invokeExact(intervalMs);
            started = true;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public String poll() {
        if (!started) {
            throw new IllegalStateException("采样器未启动，请先调用 start()");
        }
        try {
            int size = (int) SNAPSHOT_SIZE.invokeExact();
            if (size <= 0) {
                return null;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocate((long) size);
                int copied = (int) GET_SNAPSHOT.invokeExact(segment, size);
                if (copied != size) {
                    log.warn("Snapshot size mismatch: expected={}, got={}", size, copied);
                    return null;
                }
                byte[] bytes = segment.toArray(ValueLayout.JAVA_BYTE);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public void stop() {
        if (started) {
            try {
                STOP_SAMPLER.invokeExact();
                started = false;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    @Override
    public void close() {
        stop();
    }
}