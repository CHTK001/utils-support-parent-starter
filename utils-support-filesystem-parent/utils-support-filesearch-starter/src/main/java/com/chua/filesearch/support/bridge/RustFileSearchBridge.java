package com.chua.filesearch.support.bridge;

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
import java.util.function.Consumer;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Java FFM (Foreign Function &amp; Memory API) 的 Rust 文件搜索原生库桥接。
 *
 * <p>负责从 classpath 提取 {@code rust_filesearch} 原生库、绑定下述导出符号，
 * 并通过 upcall stub 将原生回调转换为 {@link FileResultData} 流：
 * <ul>
 *   <li>fast_search_by_name / fast_search_by_size / fast_search_by_path</li>
 *   <li>fast_get_tree / fast_get_version / fast_search_cancel</li>
 * </ul>
 *
 * @author CH
 */
public final class RustFileSearchBridge {

    private static final Logger log = LoggerFactory.getLogger(RustFileSearchBridge.class);

    /** 原生库解包目标目录（临时目录） */
    private static final String LIB_TARGET_DIR = System.getProperty("java.io.tmpdir");

    /** 原生库共享 Arena（持有 libraryLookup 的生命周期） */
    private static Arena ARENA;

    /** 已加载原生库的符号查找器 */
    private static SymbolLookup LIBRARY;

    /** FFM 下行调用器 */
    private static final Linker LINKER = Linker.nativeLinker();

    private static MethodHandle searchByNameHandle;
    private static MethodHandle searchBySizeHandle;
    private static MethodHandle searchByPathHandle;
    private static MethodHandle getTreeHandle;
    private static MethodHandle getVersionHandle;
    private static MethodHandle cancelHandle;

    /** upcall 方法句柄（第 12 个参数为 Consumer，绑定后供 upcallStub 使用） */
    private static final MethodHandle CALLBACK_WITH_CONSUMER;

    /** 原生库是否已加载完成 */
    private static volatile boolean loaded;

    /** 加载锁 */
    private static final Object LOAD_LOCK = new Object();

    static {
        try {
            CALLBACK_WITH_CONSUMER = MethodHandles.lookup().findStatic(RustFileSearchBridge.class, "upcallCallback",
                    MethodType.methodType(void.class,
                            MemorySegment.class, MemorySegment.class,
                            long.class, long.class, long.class, long.class, long.class,
                            int.class, int.class, int.class, int.class,
                            Consumer.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        loadLibrary();
    }

    /**
     * 创建 RustFileSearchBridge 实例
     */
    private RustFileSearchBridge() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 提取并加载 rust_filesearch 原生库，成功后绑定全部导出符号。
     * <p>幂等且线程安全；重复调用在已加载时直接返回。</p>
     */
    public static void loadLibrary() {
        if (loaded) {
            return;
        }
        synchronized (LOAD_LOCK) {
            if (loaded) {
                return;
            }
            try {
                NativeLoader.of("rust_filesearch")
                        .toTarget(Path.of(LIB_TARGET_DIR))
                        .glob("rust_filesearch.*")
                        .withMd5(true)
                        .extractOnly(false)
                        .load();
                String libFileName = NativeUtils.getLibraryFileName("rust_filesearch");
                Path libPath = Path.of(LIB_TARGET_DIR).resolve(libFileName);
                if (!Files.exists(libPath)) {
                    log.warn("Native library not found after extract: {}", libPath);
                    return;
                }
                ARENA = Arena.ofShared();
                LIBRARY = SymbolLookup.libraryLookup(libPath, ARENA);
                bindFunctions();
                loaded = true;
                log.info("File search native library loaded: {}", libPath);
            } catch (Throwable e) {
                log.error("Failed to load file search native library: {}", e.getMessage(), e);
                loaded = false;
            }
        }
    }

    /**
     * 绑定原生库导出符号到下行方法句柄。
     *
     * @throws Throwable 符号缺失或句柄创建失败时抛出
     */
    private static void bindFunctions() throws Throwable {
        if (LIBRARY == null || ARENA == null) {
            throw new IllegalStateException("Native library not loaded");
        }
        Linker linker = Linker.nativeLinker();

        MemorySegment searchByNameSym = LIBRARY.find("fast_search_by_name")
                .orElseThrow(() -> new RuntimeException("fast_search_by_name not found"));
        searchByNameHandle = linker.downcallHandle(searchByNameSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MemorySegment searchBySizeSym = LIBRARY.find("fast_search_by_size")
                .orElseThrow(() -> new RuntimeException("fast_search_by_size not found"));
        searchBySizeHandle = linker.downcallHandle(searchBySizeSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MemorySegment searchByPathSym = LIBRARY.find("fast_search_by_path")
                .orElseThrow(() -> new RuntimeException("fast_search_by_path not found"));
        searchByPathHandle = linker.downcallHandle(searchByPathSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MemorySegment getTreeSym = LIBRARY.find("fast_get_tree")
                .orElseThrow(() -> new RuntimeException("fast_get_tree not found"));
        getTreeHandle = linker.downcallHandle(getTreeSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MemorySegment getVersionSym = LIBRARY.find("fast_get_version")
                .orElseThrow(() -> new RuntimeException("fast_get_version not found"));
        getVersionHandle = linker.downcallHandle(getVersionSym,
                FunctionDescriptor.of(ValueLayout.ADDRESS));

        MemorySegment cancelSym = LIBRARY.find("fast_search_cancel")
                .orElseThrow(() -> new RuntimeException("fast_search_cancel not found"));
        cancelHandle = linker.downcallHandle(cancelSym,
                FunctionDescriptor.ofVoid());
    }

    /**
     * 按名称模式搜索文件。
     *
     * @param rootDir    搜索根目录
     * @param pattern    名称匹配模式
     * @param maxResults 最大结果数
     * @param consumer   结果回调
     * @return 错误码，0 表示成功；调用异常时返回 -99
     */
    public static int searchByName(String rootDir, String pattern, int maxResults, Consumer<FileResultData> consumer) {
        checkLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rootSeg = arena.allocateFrom(rootDir, StandardCharsets.UTF_8);
            MemorySegment patSeg = arena.allocateFrom(pattern, StandardCharsets.UTF_8);
            MemorySegment stub = createCallbackStub(consumer, arena);
            return (int) searchByNameHandle.invokeExact(rootSeg, patSeg, maxResults, stub);
        } catch (Throwable e) {
            log.error("searchByName call failed: {}", e.getMessage(), e);
            return -99;
        }
    }

    /**
     * 按大小范围搜索文件。
     *
     * @param rootDir    搜索根目录
     * @param minSize    最小字节数
     * @param maxSize    最大字节数
     * @param maxResults 最大结果数
     * @param consumer   结果回调
     * @return 错误码，0 表示成功；调用异常时返回 -99
     */
    public static int searchBySize(String rootDir, long minSize, long maxSize, int maxResults,
            Consumer<FileResultData> consumer) {
        checkLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rootSeg = arena.allocateFrom(rootDir, StandardCharsets.UTF_8);
            MemorySegment stub = createCallbackStub(consumer, arena);
            return (int) searchBySizeHandle.invokeExact(rootSeg, minSize, maxSize, maxResults, stub);
        } catch (Throwable e) {
            log.error("searchBySize call failed: {}", e.getMessage(), e);
            return -99;
        }
    }

    /**
     * 按路径模式搜索文件。
     *
     * @param rootDir     搜索根目录
     * @param pathPattern 路径匹配模式
     * @param maxResults  最大结果数
     * @param consumer    结果回调
     * @return 错误码，0 表示成功；调用异常时返回 -99
     */
    public static int searchByPath(String rootDir, String pathPattern, int maxResults,
            Consumer<FileResultData> consumer) {
        checkLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rootSeg = arena.allocateFrom(rootDir, StandardCharsets.UTF_8);
            MemorySegment patSeg = arena.allocateFrom(pathPattern, StandardCharsets.UTF_8);
            MemorySegment stub = createCallbackStub(consumer, arena);
            return (int) searchByPathHandle.invokeExact(rootSeg, patSeg, maxResults, stub);
        } catch (Throwable e) {
            log.error("searchByPath call failed: {}", e.getMessage(), e);
            return -99;
        }
    }

    /**
     * 获取目录树。
     *
     * @param rootDir    搜索根目录
     * @param maxDepth   最大深度
     * @param maxResults 最大结果数
     * @param consumer   结果回调
     * @return 错误码，0 表示成功；调用异常时返回 -99
     */
    public static int getTree(String rootDir, int maxDepth, int maxResults, Consumer<FileResultData> consumer) {
        checkLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rootSeg = arena.allocateFrom(rootDir, StandardCharsets.UTF_8);
            MemorySegment stub = createCallbackStub(consumer, arena);
            return (int) getTreeHandle.invokeExact(rootSeg, maxDepth, maxResults, stub);
        } catch (Throwable e) {
            log.error("getTree call failed: {}", e.getMessage(), e);
            return -99;
        }
    }

    /**
     * 获取原生库版本号。
     *
     * @return 版本字符串，未加载或读取失败时返回 "unknown"
     */
    public static String getVersion() {
        checkLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) getVersionHandle.invokeExact();
            if (MemorySegment.NULL.equals(result)) {
                return "unknown";
            }
            return result.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            log.error("getVersion call failed: {}", e.getMessage(), e);
            return "unknown";
        }
    }

    /**
     * 取消进行中的搜索。
     */
    public static void cancel() {
        if (!loaded) {
            return;
        }
        try {
            cancelHandle.invokeExact();
        } catch (Throwable e) {
            log.error("cancel call failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 原生库是否已加载。
     *
     * @return 已加载返回 true
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 校验原生库已加载，否则抛出状态异常。
     */
    private static void checkLoaded() {
        if (!loaded) {
            throw new IllegalStateException(
                    "File search native library not loaded. Check rust_filesearch in classpath:/native/ or java.library.path.");
        }
    }

    /**
     * 为指定回调创建原生 upcall stub。
     *
     * @param consumer 结果回调
     * @param arena    stub 生命周期归属的 Arena
     * @return upcall stub 内存段
     */
    private static MemorySegment createCallbackStub(Consumer<FileResultData> consumer, Arena arena) {
        try {
            MethodHandle bound = MethodHandles.insertArguments(CALLBACK_WITH_CONSUMER, 11, consumer);
            return LINKER.upcallStub(bound, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    arena);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create upcall stub", e);
        }
    }

    /**
     * 原生回调入口：将 C ABI 参数解码为 {@link FileResultData} 后交给 Consumer。
     *
     * @param pathPtr       路径指针（UTF-8）
     * @param extPtr        扩展名指针（UTF-8，可为空串）
     * @param size          文件大小
     * @param allocatedSize 分配大小
     * @param lastModified  最后修改时间
     * @param usnRecordId   USN 记录 ID
     * @param parentFileId  父目录文件 ID
     * @param pathLen       路径字节长度
     * @param isDirectory   是否目录（1 是 / 0 否）
     * @param extLen        扩展名字节长度
     * @param attributes    文件属性
     * @param consumer      绑定的结果回调
     */
    private static void upcallCallback(MemorySegment pathPtr, MemorySegment extPtr,
            long size, long allocatedSize, long lastModified, long usnRecordId, long parentFileId,
            int pathLen, int isDirectory, int extLen, int attributes,
            Consumer<FileResultData> consumer) {
        try {
            String path = pathPtr.reinterpret(pathLen + 1L).getString(0, StandardCharsets.UTF_8);
            String extension = extPtr.reinterpret(extLen + 1L).getString(0, StandardCharsets.UTF_8);
            if (extension.isEmpty()) {
                extension = null;
            }
            String parentDir = "";
            int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (lastSep > 0) {
                parentDir = path.substring(0, lastSep);
            }
            FileResultData data = new FileResultData(path, size, allocatedSize,
                    isDirectory == 1, extension, attributes, lastModified, usnRecordId, parentFileId);
            consumer.accept(data);
        } catch (Throwable e) {
            log.debug("upcall callback error: {}", e.getMessage());
        }
    }

    /**
     * 文件搜索结果数据载体
     *
     * @param path          完整路径
     * @param size          文件大小
     * @param allocatedSize 分配大小
     * @param isDirectory   是否目录
     * @param extension     扩展名（无则为 null）
     * @param attributes    文件属性
     * @param lastModified  最后修改时间
     * @param usnRecordId   USN 记录 ID
     * @param parentFileId  父目录文件 ID
     * @author CH
     */
    public record FileResultData(
            String path,
            long size,
            long allocatedSize,
            boolean isDirectory,
            String extension,
            int attributes,
            long lastModified,
            long usnRecordId,
            long parentFileId) {
    }
}
