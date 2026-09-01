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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SQLite update_hook 原生连接封装。
 *
 * <p>通过 Java FFM（Project Panama）绑定 {@code sqlite3_hook.dll}。
 * 写操作通过 {@link #exec(String)} 执行，变更事件在 exec() 返回后同步排空并推送到
 * {@link #changes()} 响应式流。支持多订阅者回放最近 1024 条事件。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class SqliteHookConnection implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static volatile SymbolLookup SYM_LOOKUP;
    private static final int MAX_REPLAY = 1024;

    private static volatile MethodHandle HOOK_OPEN_HANDLE;
    private static volatile MethodHandle HOOK_POLL_HANDLE;
    private static volatile MethodHandle HOOK_EXEC_HANDLE;
    private static volatile MethodHandle HOOK_CLOSE_HANDLE;
    private static volatile boolean LIBRARY_RESOLVED = false;
    private static volatile boolean LIBRARY_OK = false;

    private final MemorySegment handle;
    private final Sinks.Many<SqliteChangeEvent> sink;
    /** 最近 N 条事件用于重放 */
    private final Deque<SqliteChangeEvent> eventBuffer = new ArrayDeque<>(MAX_REPLAY);
    /** 全局事件序号 */
    private final AtomicInteger seq = new AtomicInteger(0);

    public static SqliteHookConnection open(String dbPath) {
        if (!loadLibrary()) return null;
        try (var arena = Arena.ofConfined()) {
            MemorySegment h = (MemorySegment) HOOK_OPEN_HANDLE.invoke(arena.allocateFrom(dbPath, StandardCharsets.UTF_8));
            return (h != null && !h.equals(MemorySegment.NULL)) ? new SqliteHookConnection(h) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private SqliteHookConnection(MemorySegment handle) {
        this.handle = handle;
        @SuppressWarnings("rawtypes")
        Sinks.Many<?> raw = Sinks.many().multicast().onBackpressureBuffer(MAX_REPLAY);
        this.sink = (Sinks.Many<SqliteChangeEvent>) raw;
    }

    private static boolean loadLibrary() {
        if (LIBRARY_RESOLVED) return LIBRARY_OK;
        synchronized (SqliteHookConnection.class) {
            if (LIBRARY_RESOLVED) return LIBRARY_OK;
            try {
                String platform = System.getProperty("os.name").toLowerCase().contains("win")
                        ? "windows-x86_64" : "linux-x86_64";
                String libName = "sqlite3_hook.dll";
                String path = "/native/" + platform + "/" + libName;
                InputStream is = SqliteHookConnection.class.getResourceAsStream(path);
                if (is == null) {
                    is = SqliteHookConnection.class.getResourceAsStream("/native/" + libName);
                }
                if (is == null) {
                    System.loadLibrary("sqlite3_hook");
                } else {
                    Path tmp = Files.createTempFile("sqlite3_hook_", "_" + libName);
                    Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                    tmp.toFile().deleteOnExit();
                    System.load(tmp.toAbsolutePath().toString());
                    is.close();
                }
                SYM_LOOKUP = SymbolLookup.loaderLookup();
                HOOK_OPEN_HANDLE = bind("hook_open", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_POLL_HANDLE = bind("hook_poll", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                HOOK_EXEC_HANDLE = bind("hook_exec", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_CLOSE_HANDLE = bind("hook_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                LIBRARY_OK = true;
                LIBRARY_RESOLVED = true;
                return true;
            } catch (Throwable e) {
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

    /**
     * 执行 SQL 并通过 update_hook 触发变更事件。
     */
    public int exec(String sql) {
        try (var arena = Arena.ofConfined()) {
            int rc = (int) HOOK_EXEC_HANDLE.invoke(handle, arena.allocateFrom(sql, StandardCharsets.UTF_8));
            drainBufferSync();
            return rc;
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * 变更事件响应式流。
     *
     * <p>新订阅者会先收到最近 {@value MAX_REPLAY} 条历史事件（重放），
     * 然后持续接收新事件。</p>
     */
    public Flux<SqliteChangeEvent> changes() {
        return Flux.defer(() -> {
            int lastSeq = seq.get();
            // 重放历史事件
            List<SqliteChangeEvent> replay = new ArrayList<>(eventBuffer);
            // 创建独立订阅，避免共享 state
            Sinks.Many<SqliteChangeEvent> subSink = Sinks.many().multicast().onBackpressureBuffer(MAX_REPLAY);
            // 启动后台任务：将后续事件桥接到子 sink
            int[] emittedAfterReplay = {0};
            Runnable bridge = () -> {
                while (true) {
                    int currentSeq = seq.get();
                    if (currentSeq > lastSeq + emittedAfterReplay[0]) {
                        // 有新事件，从 buffer 中取
                        int start = Math.max(0, currentSeq - MAX_REPLAY);
                        for (int i = start; i < currentSeq; i++) {
                            int idx = i - start;
                            if (idx < replay.size()) {
                                // 已在 replay 中，跳过
                            }
                        }
                        // 直接取 buffer 尾部新事件
                        int needed = currentSeq - lastSeq - emittedAfterReplay[0];
                        for (int k = 0; k < needed; k++) {
                            int bufIdx = eventBuffer.size() - needed + k;
                            if (bufIdx >= 0 && bufIdx < eventBuffer.size()) {
                                SqliteChangeEvent ev = eventBuffer.toArray(new SqliteChangeEvent[0])[bufIdx];
                                subSink.tryEmitNext(ev);
                            }
                        }
                        emittedAfterReplay[0] += needed;
                    }
                    try { Thread.sleep(10); } catch (InterruptedException ignored) { break; }
                }
            };
            Thread bridgeThread = new Thread(bridge, "sqlite-hook-bridge");
            bridgeThread.setDaemon(true);
            bridgeThread.start();

            // 发射重放 + 实时事件
            Flux<SqliteChangeEvent> replayFlux = Flux.fromIterable(replay);
            Flux<SqliteChangeEvent> liveFlux = subSink.asFlux();
            return replayFlux.concatWith(liveFlux);
        });
    }

    @Override
    public void close() {
        sink.tryEmitComplete();
        try { HOOK_CLOSE_HANDLE.invoke(handle); } catch (Throwable ignored) {}
    }

    public boolean isOpen() {
        return handle != null && !handle.equals(MemorySegment.NULL);
    }

    private void drainBufferSync() {
        int polled = 0;
        MemorySegment bufSeg = null;
        try (var arena = Arena.ofConfined()) {
            bufSeg = arena.allocate(512);
            while (true) {
                int len = (int) HOOK_POLL_HANDLE.invoke(handle, bufSeg, 512);
                if (len <= 0) break;
                String json = bufSeg.getString(0, StandardCharsets.UTF_8);
                SqliteChangeEvent event = parseEvent(json);
                if (event != null) {
                    int s = seq.incrementAndGet();
                    // 加入缓冲区（限制大小）
                    if (eventBuffer.size() >= MAX_REPLAY) {
                        eventBuffer.pollFirst();
                    }
                    eventBuffer.addLast(event);
                    // 推送给所有订阅者
                    sink.tryEmitNext(event);
                }
                polled++;
            }
        } catch (Throwable e) {
            // drain error
        }
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
