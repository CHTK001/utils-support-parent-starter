package com.chua.sqlite.support.engine;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SqliteHookConnection implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static volatile SymbolLookup SYM_LOOKUP;
    private static final int MAX_REPLAY = 2048;
    private static final int DRAIN_TIMEOUT_MS = 100;

    private static volatile MethodHandle HOOK_OPEN_HANDLE;
    private static volatile MethodHandle HOOK_WAIT_HANDLE;
    private static volatile MethodHandle HOOK_EXEC_HANDLE;
    private static volatile MethodHandle HOOK_CLOSE_HANDLE;
    private static volatile boolean LIBRARY_RESOLVED = false;
    private static volatile boolean LIBRARY_OK = false;

    private final MemorySegment handle;
    private final Sinks.Many<SqliteChangeEvent> sink;
    private final List<SqliteChangeEvent> allEvents = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<Thread> drainThread = new AtomicReference<>();

    public static SqliteHookConnection open(String dbPath) {
        if (!loadLibrary()) return null;
        try (var arena = Arena.ofConfined()) {
            MemorySegment h = (MemorySegment) HOOK_OPEN_HANDLE.invoke(arena.allocateFrom(dbPath, StandardCharsets.UTF_8));
            if (h == null || h.equals(MemorySegment.NULL)) return null;
            SqliteHookConnection conn = new SqliteHookConnection(h);
            conn.startDrainThread();
            return conn;
        } catch (Throwable e) {
            System.err.println("[OPEN] error: " + e);
            return null;
        }
    }

    private SqliteHookConnection(MemorySegment handle) {
        this.handle = handle;
        this.sink = Sinks.many().multicast().onBackpressureBuffer(MAX_REPLAY);
    }

    private void startDrainThread() {
        Thread t = new Thread(() -> {
            System.out.println("[DRAIN] thread started: " + Thread.currentThread().getName());
            try (var arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(512);
                int loops = 0;
                while (running.get()) {
                    try {
                        int len = (int) HOOK_WAIT_HANDLE.invoke(handle, buf, 512, DRAIN_TIMEOUT_MS);
                        loops++;
                        System.out.println("[DRAIN] loop=" + loops + " len=" + len + " handle=" + handle);
                        if (len <= 0) continue;
                        String json = buf.getString(0, StandardCharsets.UTF_8);
                        System.out.println("[DRAIN] json=" + json);
                        SqliteChangeEvent event = parseEvent(json);
                        if (event != null) {
                            allEvents.add(event);
                            if (allEvents.size() > MAX_REPLAY) {
                                allEvents.subList(0, allEvents.size() - MAX_REPLAY).clear();
                            }
                            System.out.println("[DRAIN] emitting event: " + event.getType());
                            sink.tryEmitNext(event);
                        }
                    } catch (Throwable e) {
                        System.err.println("[DRAIN] inner error: " + e);
                        e.printStackTrace();
                    }
                }
            } catch (Throwable e) {
                System.err.println("[DRAIN] outer error: " + e);
                e.printStackTrace();
                if (running.get()) sink.tryEmitError(e);
            }
            System.out.println("[DRAIN] exiting loop");
        }, "sqlite-hook-drain");
        t.setDaemon(true);
        t.start();
        drainThread.set(t);
    }

    private static boolean loadLibrary() {
        if (LIBRARY_RESOLVED) return LIBRARY_OK;
        synchronized (SqliteHookConnection.class) {
            if (LIBRARY_RESOLVED) return LIBRARY_OK;
            try {
                String platform = System.getProperty("os.name").toLowerCase().contains("win")
                        ? "windows-x86_64" : "linux-x86_64";
                String libName = System.getProperty("os.name").toLowerCase().contains("win")
                        ? "sqlite3_hook.dll" : "libsqlite3_hook.so";
                String path = "/native/" + platform + "/" + libName;
                InputStream is = SqliteHookConnection.class.getResourceAsStream(path);
                if (is == null) {
                    is = SqliteHookConnection.class.getResourceAsStream("/native/" + libName);
                }
                if (is == null) {
                    System.loadLibrary(libName.replace(".dll", "").replace("lib", ""));
                } else {
                    Path tmp = Files.createTempFile("sqlite3_hook_", "_" + libName);
                    Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                    tmp.toFile().deleteOnExit();
                    System.load(tmp.toAbsolutePath().toString());
                    is.close();
                }
                SYM_LOOKUP = SymbolLookup.loaderLookup();
                HOOK_OPEN_HANDLE  = bind("hook_open",  FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_WAIT_HANDLE  = bind("hook_wait",  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                HOOK_EXEC_HANDLE  = bind("hook_exec",  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_CLOSE_HANDLE = bind("hook_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                LIBRARY_OK = true;
                LIBRARY_RESOLVED = true;
                System.out.println("[LIB] loaded successfully");
                return true;
            } catch (Throwable e) {
                LIBRARY_RESOLVED = true;
                System.err.println("[LIB] error: " + e);
                e.printStackTrace();
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
        try (var arena = Arena.ofConfined()) {
            return (int) HOOK_EXEC_HANDLE.invoke(handle, arena.allocateFrom(sql, StandardCharsets.UTF_8));
        } catch (Throwable e) {
            System.err.println("[EXEC] error: " + e);
            return -1;
        }
    }

    public Flux<SqliteChangeEvent> changes() {
        List<SqliteChangeEvent> snap = new ArrayList<>(allEvents);
        return Flux.concat(Flux.fromIterable(snap), sink.asFlux().skip(snap.size()));
    }

    @Override
    public void close() {
        System.out.println("[CLOSE] closing, running=" + running.get() + " handle=" + handle);
        running.set(false);
        Thread t = drainThread.getAndSet(null);
        if (t != null) {
            try { t.join(2000); System.out.println("[CLOSE] drain thread joined"); } catch (InterruptedException ignored) {}
        }
        sink.tryEmitComplete();
        try { HOOK_CLOSE_HANDLE.invoke(handle); System.out.println("[CLOSE] hook_close done"); } catch (Throwable ignored) {}
    }

    public boolean isOpen() {
        return handle != null && !handle.equals(MemorySegment.NULL) && running.get();
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
