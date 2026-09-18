package com.chua.sqlite.support.engine;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQLite 真响应式 Hook — 基于 OS 原生异步 I/O（IOCP / io_uring）。
 *
 * <p><b>核心特性：</b></p>
 * <ul>
 *   <li>零专用线程：使用 OS 原生异步 I/O，事件通过回调直接推送</li>
 *   <li>零轮询：无 select/poll/epoll，无 CPU 空转</li>
 *   <li>背压：FluxSink.OverflowStrategy.LATEST，消费者慢时只保留最新</li>
 *   <li>非阻塞：exec 异步执行，events 完全事件驱动</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * try (SqliteReactorHook hook = new SqliteReactorHook("mydb.sqlite")) {
 *     // 事件流：完全非阻塞，OS 异步推送
 *     hook.events()
 *         .filter(e -> e.getTable().equals("users"))
 *         .subscribe(e -> System.out.println(e.getType() + " on " + e.getTable()));
 *
 *     // 执行 SQL：非阻塞
 *     hook.exec("INSERT INTO users(name) VALUES('Alice')")
 *         .subscribe();
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class SqliteReactorHook implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static volatile SymbolLookup SYM_LOOKUP;
    private static final int EVENT_BUF_SIZE = 512;

    private static volatile MethodHandle HOOK_OPEN_ASYNC_HANDLE;
    private static volatile MethodHandle HOOK_EXEC_ASYNC_HANDLE;
    private static volatile MethodHandle HOOK_CLOSE_ASYNC_HANDLE;
    private static volatile boolean LIBRARY_RESOLVED = false;
    private static volatile boolean LIBRARY_OK = false;

    /** 实例 ID，用于全局回调分发 */
    private final long instanceId;
    private final MemorySegment handle;
    private final Sinks.Many<SqliteChangeEvent> eventSink;
    private final Flux<SqliteChangeEvent> flux;

    /** 全局实例映射（native 回调通过 ID 找到 Java 实例） */
    private static final ConcurrentHashMap<Long, SqliteReactorHook> INSTANCES = new ConcurrentHashMap<>();
    private static final AtomicLong INSTANCE_COUNTER = new AtomicLong(0);

    /** 保持 Arena 存活（回调期间不能释放） */
    private Arena callbackArena;

    /** 防止 close() 重入 */
    private volatile boolean closed = false;

    /**
    * 创建真响应式 SQLite Hook。
    *
    * @param dbPath SQLite 数据库文件路径
    */
    public SqliteReactorHook(String dbPath) {
        if (!loadLibrary()) {
            throw new UnsatisfiedLinkError("Failed to load sqlite3_hook native library");
        }

        this.instanceId = INSTANCE_COUNTER.incrementAndGet();
        this.eventSink = Sinks.many().multicast().onBackpressureBuffer(256, false);
        this.flux = eventSink.asFlux();

        // 保持 Arena 存活（用于回调函数指针）— 必须用 ofShared，因为 native 回调在 io_uring 线程触发
        this.callbackArena = Arena.ofShared();
        INSTANCES.put(instanceId, this);

        try {
            // 创建 C 回调函数指针
            // hook_event_fn 签名: void (*)(const char *json, void *user_data)
            // onNativeEvent 是 static 方法，通过 user_data 分发到实例
            MethodHandle mh = onEventHandle();
            FunctionDescriptor fd = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            MemorySegment callback = LINKER.upcallStub(mh, fd, callbackArena);

            // user_data 传递 instanceId（转换为指针）
            MemorySegment userId = callbackArena.allocate(ValueLayout.JAVA_LONG);
            userId.set(ValueLayout.JAVA_LONG, 0, instanceId);

            this.handle = (MemorySegment) HOOK_OPEN_ASYNC_HANDLE.invoke(
                callbackArena.allocateFrom(dbPath, StandardCharsets.UTF_8),
                callback,
                userId
            );

            if (this.handle == null || this.handle.equals(MemorySegment.NULL)) {
                throw new RuntimeException("hook_open_async returned NULL");
            }
        } catch (Throwable e) {
            INSTANCES.remove(instanceId);
            if (callbackArena != null) {
                callbackArena.close();
            }
            throw new RuntimeException("Failed to open async hook", e);
        }
    }

    /**
    * 获取变更事件流。
    * <p>完全非阻塞，事件由 OS 异步 I/O 完成时直接推送。</p>
    *
    * @return 变更事件 Flux，支持背压（LATEST 策略）
    */
    public Flux<SqliteChangeEvent> events() {
        return flux;
    }

    /**
    * 执行 SQL（非阻塞）。
    *
    * @param sql UTF-8 编码的 SQL 语句
    * @return SQLite 返回码（0 = 成功），Mono 包装
    */
    public Mono<Integer> exec(String sql) {
        return Mono.fromCallable(() -> execSync(sql))
                  .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * execSync。
     *
     * @param sql SQL，不允许为 null
     * @return 结果数值
     */
    private int execSync(String sql) {
        try (var arena = Arena.ofConfined()) {
            return (int) HOOK_EXEC_ASYNC_HANDLE.invoke(
                handle,
                arena.allocateFrom(sql, StandardCharsets.UTF_8)
            );
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
    * C 回调入口（被 OS 异步 I/O 完成时调用）。
    * <p>此方法在 native 线程调用，直接推送事件到 Reactor 流。</p>
    *
    * @param jsonPtr   事件 JSON 字符串指针
    * @param userIdPtr 用户 ID 指针（存储 instanceId）
    */
    @SuppressWarnings("unused")
    private static void onNativeEvent(MemorySegment jsonPtr, MemorySegment userIdPtr) {
        if (jsonPtr == null || jsonPtr.equals(MemorySegment.NULL)) {
            return;
        }

        try {
            long userId = userIdPtr.reinterpret(Long.MAX_VALUE).get(ValueLayout.JAVA_LONG, 0);
            SqliteReactorHook instance = INSTANCES.get(userId);

            if (instance == null) {
                return;
            }

            String json = jsonPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
            SqliteChangeEvent event = parseEvent(json);
            if (event != null) {
                instance.eventSink.tryEmitNext(event);
            }
        } catch (Exception ignored) {
        }
    }
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        INSTANCES.remove(instanceId);

        eventSink.tryEmitComplete();

        try {
            if (handle != null && !handle.equals(MemorySegment.NULL)) {
                HOOK_CLOSE_ASYNC_HANDLE.invoke(handle);
            }
        } catch (Throwable ignored) {
        }

        if (callbackArena != null) {
            try {
                callbackArena.close();
            } catch (Throwable ignored) {
            }
            callbackArena = null;
        }
    }

    /**
     * 是否打开。
     *
     * @return 是否成功（true 表示成功）
     */
    public boolean isOpen() {
        return !closed && handle != null && !handle.equals(MemorySegment.NULL);
    }

    /* ═══════════════════════════════════════════════════════════════
     *  内部实现
     * ═══════════════════════════════════════════════════════════════ */

    /**
    * 创建 onNativeEvent 的 MethodHandle（用于 FFM upcall）。
    * <p>注意：这是一个 static 方法，通过 user_data 分发到实例。</p>
    * @return 方法处理 对象
    */
    private static MethodHandle onEventHandle() {
        try {
            return MethodHandles.lookup().findStatic(
                SqliteReactorHook.class,
                "onNativeEvent",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)
            );
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new UnsatisfiedLinkError("Cannot find onNativeEvent: " + e.getMessage());
        }
    }

    /**
     * 加载Library。
     *
     * @return 是否成功（true 表示成功）
     */
    private static boolean loadLibrary() {
        if (LIBRARY_RESOLVED) {
            return LIBRARY_OK;
        }
        synchronized (SqliteReactorHook.class) {
            if (LIBRARY_RESOLVED) {
                return LIBRARY_OK;
            }
            try {
                // 统一走项目受控的原生库加载体系：由 NativeLoader 按平台目录抽取、MD5 去重后加载，
                // 避免业务代码直接调用 System.load（与同包 SqliteHookConnection 保持一致用法）
                NativeLoader.of("sqlite3-hook")
                        .glob(NativeUtils.getLibraryFileName("sqlite3_hook"))
                        .toTarget(NativeUtils.tempRoot().resolve("sqlite3-hook").toFile().getAbsolutePath())
                        .load();
                SYM_LOOKUP = SymbolLookup.loaderLookup();
                HOOK_OPEN_ASYNC_HANDLE = bind("hook_open_async",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_EXEC_ASYNC_HANDLE = bind("hook_exec_async",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_CLOSE_ASYNC_HANDLE = bind("hook_close_async",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                LIBRARY_OK = true;
                LIBRARY_RESOLVED = true;
                return true;
            } catch (Throwable e) {
                LIBRARY_RESOLVED = true;
                return false;
            }
        }
    }

    /**
     * 绑定。
     *
     * @param name 名称，不允许为 null
     * @param desc 描述，不允许为 null
     * @return 方法处理 对象
     */
    private static MethodHandle bind(String name, FunctionDescriptor desc) {
        MemorySegment sym = SYM_LOOKUP.find(name)
            .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(sym, desc);
    }

    /**
     * 解析Event。
     *
     * @param json 方法入参 json
     * @return SqliteChangeEvent 对象
     */
    static SqliteChangeEvent parseEvent(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }

        // 验证 JSON 格式
        String type = extractString(json, "type");
        if (type == null) {
            return null; // 无效 JSON 或缺少 type 字段
        }

        String table = extractString(json, "table");
        long rowId = extractLong(json, "rowId");

        SqliteChangeEvent.Type eventType;
        switch (type.toUpperCase()) {
            case "INSERT" -> eventType = SqliteChangeEvent.Type.INSERT;
            case "UPDATE" -> eventType = SqliteChangeEvent.Type.UPDATE;
            case "DELETE" -> eventType = SqliteChangeEvent.Type.DELETE;
            default -> eventType = SqliteChangeEvent.Type.UPDATE;
        }

        String safeTable = (table == null || table.isEmpty()) ? "(unknown)" : table;
        return switch (eventType) {
            case INSERT -> SqliteChangeEvent.insert(safeTable, rowId);
            case UPDATE -> SqliteChangeEvent.update(safeTable, rowId);
            case DELETE -> SqliteChangeEvent.delete(safeTable, rowId);
        };
    }

    /**
     * extract字符串。
     *
     * @param json 方法入参 json
     * @param key 键，不允许为 null
     * @return 结果字符串
     */
    private static String extractString(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) {
            return null;
        }
        int ci = json.indexOf(':', ki + key.length() + 2);
        if (ci < 0) {
            return null;
        }
        int si = json.indexOf('"', ci + 1);
        if (si < 0) {
            return null;
        }
        int ei = json.indexOf('"', si + 1);
        if (ei < 0) {
            ei = json.length() - 1;
        }
        return json.substring(si + 1, ei);
    }

    /**
     * extractLong。
     *
     * @param json 方法入参 json
     * @param key 键，不允许为 null
     * @return 结果数值
     */
    private static long extractLong(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) {
            return 0L;
        }
        int ci = json.indexOf(':', ki + key.length() + 2);
        if (ci < 0) {
            return 0L;
        }
        int end = ci + 1;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Long.parseLong(json.substring(ci + 1, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
