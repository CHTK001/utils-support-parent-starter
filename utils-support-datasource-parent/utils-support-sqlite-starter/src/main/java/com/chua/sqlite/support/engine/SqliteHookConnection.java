package com.chua.sqlite.support.engine;

import com.chua.common.support.utils.NativeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

public final class SqliteHookConnection implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static volatile SymbolLookup SYM_LOOKUP;

    private static volatile MethodHandle HOOK_OPEN_HANDLE;
    private static volatile MethodHandle HOOK_POLL_HANDLE;
    private static volatile MethodHandle HOOK_EXEC_HANDLE;
    private static volatile MethodHandle HOOK_CLOSE_HANDLE;
    private static volatile boolean LIBRARY_RESOLVED = false;
    private static volatile boolean LIBRARY_OK = false;

    private final MemorySegment handle;
    private final Sinks.Many<SqliteChangeEvent> sink;

    public static SqliteHookConnection open(String dbPath) {
        if (!loadLibrary()) {
            System.err.println("[sqlite-hook] loadLibrary FAILED");
            return null;
        }
        try (var arena = Arena.ofConfined()) {
            MemorySegment h = (MemorySegment) HOOK_OPEN_HANDLE.invoke(arena.allocateFrom(dbPath, StandardCharsets.UTF_8));
            System.err.println("[sqlite-hook] hook_open(" + dbPath + ") returned handle=" + h);
            return (h != null && !h.equals(MemorySegment.NULL)) ? new SqliteHookConnection(h) : null;
        } catch (Throwable e) {
            System.err.println("[sqlite-hook] hook_open exception: " + e.getMessage());
            e.printStackTrace(System.err);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private SqliteHookConnection(MemorySegment handle) {
        this.handle = handle;
        @SuppressWarnings("rawtypes")
        Sinks.Many<?> raw = Sinks.many().multicast().onBackpressureBuffer(1024);
        this.sink = (Sinks.Many<SqliteChangeEvent>) raw;
    }

    private static boolean loadLibrary() {
        if (LIBRARY_RESOLVED) return LIBRARY_OK;
        synchronized (SqliteHookConnection.class) {
            if (LIBRARY_RESOLVED) return LIBRARY_OK;
            try {
                System.err.println("[sqlite-hook] Loading sqlite3_hook library...");
                NativeUtils.load("sqlite3_hook", null);
                SYM_LOOKUP = SymbolLookup.loaderLookup();

                HOOK_OPEN_HANDLE  = bind("hook_open",   FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_POLL_HANDLE  = bind("hook_poll",   FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                HOOK_EXEC_HANDLE  = bind("hook_exec",   FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_CLOSE_HANDLE = bind("hook_close",  FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

                System.err.println("[sqlite-hook] Library loaded. Symbols: open=" + HOOK_OPEN_HANDLE + " poll=" + HOOK_POLL_HANDLE + " exec=" + HOOK_EXEC_HANDLE);
                LIBRARY_OK = true;
                LIBRARY_RESOLVED = true;
                return true;
            } catch (Throwable e) {
                System.err.println("[sqlite-hook] Failed to load library: " + e.getMessage());
                e.printStackTrace(System.err);
                LIBRARY_RESOLVED = true;
                return false;
            }
        }
    }

    private static MethodHandle bind(String name, FunctionDescriptor desc) {
        MemorySegment sym = SYM_LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("符号未找到: " + name));
        return LINKER.downcallHandle(sym, desc);
    }

    public int exec(String sql) {
        System.err.println("[sqlite-hook] exec('" + sql + "') handle=" + handle);
        try (var arena = Arena.ofConfined()) {
            int rc = (int) HOOK_EXEC_HANDLE.invoke(handle, arena.allocateFrom(sql, StandardCharsets.UTF_8));
            System.err.println("[sqlite-hook] exec returned rc=" + rc);
            drainBufferSync();
            return rc;
        } catch (Throwable e) {
            System.err.println("[sqlite-hook] exec exception: " + e.getMessage());
            e.printStackTrace(System.err);
            return -1;
        }
    }

    public Flux<SqliteChangeEvent> changes() {
        return sink.asFlux();
    }

    @Override
    public void close() {
        sink.tryEmitComplete();
        try {
            System.err.println("[sqlite-hook] hook_close");
            HOOK_CLOSE_HANDLE.invoke(handle);
        } catch (Throwable ignored) {}
    }

    public boolean isOpen() {
        return handle != null && !handle.equals(MemorySegment.NULL);
    }

    private void drainBufferSync() {
        int polled = 0;
        try (var scope = Arena.ofConfined()) {
            MemorySegment bufSeg = scope.allocate(512);
            while (true) {
                int len = (int) HOOK_POLL_HANDLE.invoke(handle, bufSeg, 512);
                System.err.println("[sqlite-hook] hook_poll returned len=" + len);
                if (len <= 0) break;
                String json = bufSeg.getString(0, StandardCharsets.UTF_8);
                System.err.println("[sqlite-hook] poll event JSON: " + json);
                SqliteChangeEvent event = parseEvent(json);
                if (event != null) {
                    sink.tryEmitNext(event);
                    System.err.println("[sqlite-hook] emitted: " + event);
                } else {
                    System.err.println("[sqlite-hook] parse failed: " + json);
                }
                polled++;
            }
        } catch (Throwable e) {
            System.err.println("[sqlite-hook] drain error: " + e.getMessage());
            e.printStackTrace(System.err);
        }
        System.err.println("[sqlite-hook] drainBufferSync: polled " + polled + " events");
    }

    static SqliteChangeEvent parseEvent(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            String type = extractString(json, "type");
            String table = extractString(json, "table");
            long rowId = extractLong(json, "rowId");
            SqliteChangeEvent.Type eventType;
            switch (type == null ? "" : type.toUpperCase()) {
                case "INSERT"  -> eventType = SqliteChangeEvent.Type.INSERT;
                case "UPDATE"  -> eventType = SqliteChangeEvent.Type.UPDATE;
                case "DELETE"  -> eventType = SqliteChangeEvent.Type.DELETE;
                default        -> eventType = SqliteChangeEvent.Type.UPDATE;
            }
            String safeTable = (table == null || table.isEmpty()) ? "(unknown)" : table;
            return switch (eventType) {
                case INSERT  -> SqliteChangeEvent.insert(safeTable, rowId);
                case UPDATE  -> SqliteChangeEvent.update(safeTable, rowId);
                case DELETE  -> SqliteChangeEvent.delete(safeTable, rowId);
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractString(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) return null;
        int ci = json.indexOf(':', ki + key.length() + 2);
        if (ci < 0) return null;
        int si = json.indexOf('"', ci + 1);
        if (si < 0) return null;
        int ei = json.indexOf('"', si + 1);
        if (ei < 0) ei = json.length() - 1;
        return json.substring(si + 1, ei);
    }

    private static long extractLong(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) return 0L;
        int ci = json.indexOf(':', ki + key.length() + 2);
        if (ci < 0) return 0L;
        int end = ci + 1;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try { return Long.parseLong(json.substring(ci + 1, end)); }
        catch (NumberFormatException e) { return 0L; }
    }
}
