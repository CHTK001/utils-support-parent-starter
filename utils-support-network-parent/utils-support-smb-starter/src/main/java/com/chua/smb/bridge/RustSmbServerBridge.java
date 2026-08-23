package com.chua.smb.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SMB 服务端原生库桥接 — Java 25 FFM (Panama) 绑定。
 *
 * <p>从 classpath 加载预编译的 {@code rust_smb_server} 动态库，通过 FFM API 调用 FFI 函数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class RustSmbServerBridge {

    /**
     * lib target dir
     */
    private static final String LIB_TARGET_DIR =
            NativeUtils.tempRoot().resolve("rust_smb_server").toString();

    /**
     * ARENA
     */
    private static Arena ARENA;
    /**
     * LIBRARY
     */
    private static SymbolLookup LIBRARY;
    /**
     * LINKER
     */
    private static final Linker LINKER = Linker.nativeLinker();

    /**
     * start Handle
     */
    private static MethodHandle startHandle;
    /**
     * stop Handle
     */
    private static MethodHandle stopHandle;
    /**
     * 列表 Shares Handle
     */
    private static MethodHandle listSharesHandle;
    /**
     * free String Handle
     */
    private static MethodHandle freeStringHandle;

    /**
     * loaded
     */
    private static volatile boolean loaded = false;
    /**
     * 加载 锁
     */
    private static final Object LOAD_LOCK = new Object();

    static { loadLibrary(); }

    /** 创建 RustSmbServerBridge 实例 */
    private RustSmbServerBridge() { throw new UnsupportedOperationException("Utility class"); }

    // ==================== 生命周期 ====================

    /** 加载Library */
    public static void loadLibrary() {
        if (loaded) {
            return;
        }
        synchronized (LOAD_LOCK) {
            if (loaded) {
                return;
            }
            try {
                NativeLoader.of("rust_smb_server")
                        .toTarget(Path.of(LIB_TARGET_DIR))
                        .glob("rust_smb_server.*")
                        .withMd5(true)
                        .extractOnly(false)
                        .load();

                String libFileName = NativeUtils.getLibraryFileName("rust_smb_server");
                Path libPath = Path.of(LIB_TARGET_DIR).resolve(libFileName);
                if (!Files.exists(libPath)) {
                    log.warn("SMB native library not found after extract: {}", libPath);
                    return;
                }

                ARENA = Arena.ofShared();
                LIBRARY = SymbolLookup.libraryLookup(libPath, ARENA);
                bindFunctions();
                loaded = true;
                log.info("SMB native library loaded: {}", libPath);
            } catch (Throwable e) {
                log.error("Failed to load SMB native library: {}", e.getMessage(), e);
                loaded = false;
            }
        }
    }

    @SuppressWarnings("unchecked")
    /** 绑定Functions */
    private static void bindFunctions() throws Throwable {
        if (LIBRARY == null || ARENA == null) {
            throw new IllegalStateException("Native library not loaded");
        }

        MemorySegment startSym = LIBRARY.find("smb_server_start")
                .orElseThrow(() -> new RuntimeException("smb_server_start not found"));
        startHandle = LINKER.downcallHandle(startSym,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        MemorySegment stopSym = LIBRARY.find("smb_server_stop")
                .orElseThrow(() -> new RuntimeException("smb_server_stop not found"));
        stopHandle = LINKER.downcallHandle(stopSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

        MemorySegment listSym = LIBRARY.find("smb_server_list_shares")
                .orElseThrow(() -> new RuntimeException("smb_server_list_shares not found"));
        listSharesHandle = LINKER.downcallHandle(listSym,
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        MemorySegment freeSym = LIBRARY.find("smb_server_free_string")
                .orElseThrow(() -> new RuntimeException("smb_server_free_string not found"));
        freeStringHandle = LINKER.downcallHandle(freeSym,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    // ==================== 公共 API ====================

    /**
     * 启动 SMB 服务器。
     *
     * @param bindAddr  绑定地址 (如 "0.0.0.0")
     * @param port      监听端口
     * @param shareName 共享目录名称
     * @param rootPath  本地根路径
     * @param user      用户名 (可为 null 或空，表示匿名)
     * @param password  密码 (可为 null 或空)
     * @return 正数 handle，失败抛异常
     */
    public static long start(String bindAddr, int port, String shareName, String rootPath,
                             String user, String password) {
        checkLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addrSeg = arena.allocateFrom(bindAddr, StandardCharsets.UTF_8);
            MemorySegment shareSeg = arena.allocateFrom(shareName, StandardCharsets.UTF_8);
            MemorySegment rootSeg = arena.allocateFrom(rootPath, StandardCharsets.UTF_8);
            MemorySegment userSeg = user != null && !user.isEmpty()
                    ? arena.allocateFrom(user, StandardCharsets.UTF_8) : MemorySegment.NULL;
            MemorySegment passSeg = password != null && !password.isEmpty()
                    ? arena.allocateFrom(password, StandardCharsets.UTF_8) : MemorySegment.NULL;
            long handle = (long) startHandle.invokeExact(addrSeg, port, shareSeg, rootSeg, userSeg, passSeg);
            if (handle <= 0) {
                throw new RuntimeException("SMB server start failed, code: " + handle);
            }
            return handle;
        } catch (Throwable e) {
            throw new RuntimeException("smb_server_start call failed", e);
        }
    }

    /**
     * 停止 SMB 服务器。
     *
     * @param handle smb_server_start 返回的句柄
     */
    public static void stop(long handle) {
        if (!loaded || handle <= 0) {
            return;
        }
        try {
            int rc = (int) stopHandle.invokeExact(handle);
            if (rc != 0) {
                log.warn("smb_server_stop returned {}", rc);
            }
        } catch (Throwable e) {
            throw new RuntimeException("smb_server_stop call failed", e);
        }
    }

    /**
     * 列出 share 名称。
     *
     * @param handle smb_server_start 返回的句柄
     * @return share 名称数组
     */
    public static String[] listShares(long handle) {
        checkLoaded();
        try {
            MemorySegment ptr = (MemorySegment) listSharesHandle.invokeExact(handle);
            if (MemorySegment.NULL.equals(ptr)) {
                return new String[0];
            }
            String raw = ptr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
            return java.util.Arrays.stream(raw.split("\0"))
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        } catch (Throwable e) {
            throw new RuntimeException("smb_server_list_shares call failed", e);
        }
    }

    /**
     * 原生库是否已加载。
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 校验Loaded */
    private static void checkLoaded() {
        if (!loaded) {
            throw new IllegalStateException(
                "SMB native library not loaded. Check rust_smb_server in classpath:/native/ or java.library.path.");
        }
    }
}
