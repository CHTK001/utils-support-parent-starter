package com.chua.filesearch.support.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import com.chua.filesearch.support.model.FileInfo;
import com.chua.filesearch.support.model.FileSearchCriteria;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * 文件搜索原生库桥接 - Java 25 FFM (Panama) 绑定
 *
 * @author CH
 * @since 4.0.0
 */
public final class RustFileSearchBridge {

    private static final Logger log = LoggerFactory.getLogger(RustFileSearchBridge.class);

    private static final String LIB_TARGET_DIR = System.getProperty("java.io.tmpdir") + "/rust_filesearch";

    private static Arena ARENA;
    private static SymbolLookup LIBRARY;
    private static final Linker LINKER = Linker.nativeLinker();

    private static MethodHandle searchByNameHandle;
    private static MethodHandle searchBySizeHandle;
    private static MethodHandle searchByPathHandle;
    private static MethodHandle getTreeHandle;
    private static MethodHandle getVersionHandle;
    private static MethodHandle cancelHandle;

    private static final MethodHandle CALLBACK_WITH_CONSUMER;

    static {
        MethodHandle mh;
        try {
            mh = MethodHandles.lookup().findStatic(
                    RustFileSearchBridge.class, "upcallCallback",
                    MethodType.methodType(void.class,
                            MemorySegment.class, MemorySegment.class,
                            long.class, long.class, long.class, long.class,
                            long.class, int.class, int.class, int.class, int.class,
                            Consumer.class));
        } catch (Exception e) {
            mh = null;
        }
        CALLBACK_WITH_CONSUMER = mh;
    }

    private static volatile boolean loaded = false;
    private static final Object LOAD_LOCK = new Object();

    static {
        loadLibrary();
    }

    private RustFileSearchBridge() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 加载原生库
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

    private static void bindFunctions() throws Throwable {
        if (LIBRARY == null || ARENA == null) {
            throw new IllegalStateException("Native library not loaded");
        }
        Linker linker = Linker.nativeLinker();

        MemorySegment searchByNameSym = LIBRARY.find("fast_search_by_name")
                .orElseThrow(() -> new RuntimeException("fast_search_by_name not found"));
        searchByNameHandle = linker.downcallHandle(searchByNameSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));

        MemorySegment searchBySizeSym = LIBRARY.find("fast_search_by_size")
                .orElseThrow(() -> new RuntimeException("fast_search_by_size not found"));
        searchBySizeHandle = linker.downcallHandle(searchBySizeSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));

        MemorySegment searchByPathSym = LIBRARY.find("fast_search_by_path")
                .orElseThrow(() -> new RuntimeException("fast_search_by_path not found"));
        searchByPathHandle = linker.downcallHandle(searchByPathSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));

        MemorySegment getTreeSym = LIBRARY.find("fast_get_tree")
                .orElseThrow(() -> new RuntimeException("fast_get_tree not found"));
        getTreeHandle = linker.downcallHandle(getTreeSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));

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
     * 按文件名通配符搜索
     *
     * @param rootDir    根目录
     * @param pattern    通配符模式
     * @param maxResults 最大结果数
     * @param consumer   结果回调
     * @return result count, negative means error
     */
    public static int searchByName(String rootDir, String pattern, int maxResults, Consumer<FileResultData> consumer) {
        checkLoaded();
        try (var arena = Arena.ofConfined()) {
            MemorySegment rootSeg = arena.allocateFrom(rootDir, StandardCharsets.UTF_8);
            MemorySegment patSeg = arena.allocateFrom(pattern, StandardCharsets.UTF_8);
            MemorySegment stub = createCallbackStub(consumer, arena);
            return (int) searchByNameHandle.invokeExact(
                    (MemorySegment) rootSeg,
                    (MemorySegment) patSeg,
                    maxResults,
                    (MemorySegment) stub
            );
        } catch (Throwable e) {
            log.error("searchByName call failed: {}", e.getMessage(), e);
            return -99;
        }
    }

    /**
     * 按文件大小范围搜索
     *
     * @param rootDir    根目录
     * @param minSize    最小大小（字节），-1 不限
     * @param maxSize    最大大小（字节），-1 不限
     * @param maxResults 最大结果数
     * @param consumer   结果回调
     * @return result count, negative means error
     */
    public static int searchBySize(String rootDir, long minSize, long maxSize, int maxResults, Consumer<FileResultData> consumer) {
        checkLoaded();
        try (var arena = Arena.ofConfined()) {
            MemorySegment rootSeg = arena.allocateFrom(rootDir, StandardCharsets.UTF_8);
            MemorySegment stub = createCallbackStub(consumer, arena);
            return (int) searchBySizeHandle.invokeExact(
                    (MemorySegment) rootSeg,
                    minSize,
                    maxSize,
                    maxResults,
                    (MemorySegment) stub
            );
        } catch (Throwable e) {
            log.error("searchBySize call failed: {}", e.getMessage(), e);
            return -99;
        }
    }

    /**
     * Search by path pattern.
     *
     * @param rootDir root directory
     * @param pathPattern path pattern
     * @param maxResults max results
     * @param consumer result consumer
     * @return result count, negative means error
     */
    public static int searchByPath(String rootDir, String pathPattern, int maxResults, Consumer<FileResultData> consumer) {
        checkLoaded();
        try (var arena = Arena.ofConfined()) {
            MemorySegment rootSeg = arena.allocateFrom(rootDir, StandardCharsets.UTF_8);
            MemorySegment patSeg = arena.allocateFrom(pathPattern, StandardCharsets.UTF_8);
            MemorySegment stub = createCallbackStub(consumer, arena);
            return (int) searchByPathHandle.invokeExact(
                    (MemorySegment) rootSeg,
                    (MemorySegment) patSeg,
                    maxResults,
                    (MemorySegment) stub
            );
        } catch (Throwable e) {
            log.error("searchByPath call failed: {}", e.getMessage(), e);
            return -99;
        }
    }

    /**
     * 获取目录树结构
     *
     * @param rootDir  根目录
     * @param maxDepth 最大深度，0 表示不限
     * @param consumer 结果回调
     * @return 条目数量，负数表示错误码
     */
    public static int getTree(String rootDir, int maxDepth, int maxResults, Consumer<FileResultData> consumer) {
        checkLoaded();
        try (var arena = Arena.ofConfined()) {
            MemorySegment rootSeg = arena.allocateFrom(rootDir, StandardCharsets.UTF_8);
            MemorySegment stub = createCallbackStub(consumer, arena);
            return (int) getTreeHandle.invokeExact(
                    (MemorySegment) rootSeg,
                    maxDepth,
                    maxResults,
                    (MemorySegment) stub
            );
        } catch (Throwable e) {
            log.error("getTree call failed: {}", e.getMessage(), e);
            return -99;
        }
    }

    /**
     * 获取库版本号
     *
     * @return 版本字符串
     */
    public static String getVersion() {
        checkLoaded();
        try (var arena = Arena.ofConfined()) {
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
     * 取消正在进行的搜索
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
     * 原生库是否已加载
     *
     * @return true 表示已加载
     */
    public static boolean isLoaded() {
        return loaded;
    }

    private static void checkLoaded() {
        if (!loaded) {
            throw new IllegalStateException(
                    "File search native library not loaded. Check rust_filesearch in classpath:/native/ or java.library.path.");
        }
    }

    private static MemorySegment createCallbackStub(Consumer<FileResultData> consumer, Arena arena) {
        try {
            MethodHandle bound = MethodHandles.insertArguments(CALLBACK_WITH_CONSUMER, 11, consumer);
            return LINKER.upcallStub(bound,
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    arena);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create upcall stub", e);
        }
    }

    private static void upcallCallback(
            MemorySegment pathPtr,
            MemorySegment extPtr,
            long size,
            long allocatedSize,
            long lastModified,
            long usnRecordId,
            long parentFileId,
            int pathLen,
            int isDirectory,
            int extLen,
            int attributes,
            Consumer<FileResultData> consumer) {
        try {
            String path = pathPtr.reinterpret(pathLen + 1).getString(0, StandardCharsets.UTF_8);
            String extension = extPtr.reinterpret(extLen + 1).getString(0, StandardCharsets.UTF_8);
            if (extension.isEmpty()) {
                extension = null;
            }

            String parentDir = "";
            int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (lastSep > 0) {
                parentDir = path.substring(0, lastSep);
            }

            FileResultData data = new FileResultData(
                    path, size, allocatedSize, isDirectory == 1, extension, attributes,
                    lastModified, usnRecordId, parentFileId
            );
            consumer.accept(data);
        } catch (Throwable e) {
            log.debug("upcall callback error: {}", e.getMessage());
        }
    }

    /**
     * 文件搜索结果数据载体
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
            long parentFileId
    ) {
    }
}
