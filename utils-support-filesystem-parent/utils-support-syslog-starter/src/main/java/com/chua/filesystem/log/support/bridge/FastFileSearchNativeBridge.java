package com.chua.filesystem.log.support.bridge;

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
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
* 快速文件搜索原生库桥接 - Java 25 FFM (Panama) 绑定
* <p>
* 窗口 NTFS MFT 直读搜索（需要管理员权限）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public final class FastFileSearchNativeBridge {

    /** Arena */
    private static Arena ARENA;
    /** 图书馆 */
    private static SymbolLookup LIBRARY;

    /** 搜索mfthandle */
    private static MethodHandle searchMftHandle;
    /** Cancelhandle */
    private static MethodHandle cancelHandle;

    /** 加载 */
    private static volatile boolean loaded = false;
    /** 加载_锁 */
    private static final Object LOAD_LOCK = new Object();

    /** Linker */
    private static final Linker LINKER = Linker.nativeLinker();

    static {
        loadLibrary();
    }

    /** 创建 fast文件搜索NATbridge 实例 */
    private FastFileSearchNativeBridge() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
    * 加载快速文件搜索原生库（fast_文件_搜索）。
    *
    * <p>通过 {@link NativeLoader} 将 classpath 下 {@code /native/{platform}/} 目录中匹配
    * {@code *fast_file_search*} 的动态库抽取到 JVM 临时目录下的
    * {@code chua-native/fast-file-search} 子目录并调用 {@code System.load} 完成安装；
    * 随后使用 FFM API 的 {@link SymbolLookup#libraryLookup(Path, Arena)} 以共享 Arena
    * 建立符号查找表，并调用 {@link #bindFunctions()} 绑定各原生函数句柄。
    *
    * <p>整个加载过程在 {@code LOAD_LOCK} 保护下以双重检查锁（double-checked locking）保证
    * 全局仅执行一次；若原生库安装、符号查找或函数绑定中任意环节失败，仅记录错误日志并将
    * {@code loaded} 置为 {@code false}，不影响 JVM 其余功能。未成功加载时调用 {@link #searchMft}
    * 会直接抛出 {@link IllegalStateException} 提示 MFT 搜索不可用。
    *
    * @author CH
    * @since 4.0.0.42
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
                NativeLoader.of("fast-file-search")
                        .toTarget(NativeUtils.tempRoot().resolve("fast-file-search"))
                        .glob("*fast_file_search*")
                        .load();
                Path libPath = NativeUtils.tempRoot().resolve("fast-file-search").resolve(System.mapLibraryName("fast_file_search"));
                ARENA = Arena.ofShared();
                LIBRARY = SymbolLookup.libraryLookup(libPath, ARENA);
                bindFunctions();
                loaded = true;
                log.info("Fast file search native library loaded successfully: {}", libPath);
            } catch (Throwable t) {
                log.error("Failed to load fast file search native library, MFT search unavailable: {}", t.getMessage(), t);
                loaded = false;
            }
        }
    }

    /**
    * 绑定原生函数句柄。
    *
    * <p>在原生库加载成功后，从 {@link #LIBRARY} 符号查找表中解析 {@code fast_search_mft} 与
    * {@code fast_search_cancel} 两个符号，并通过 {@link Linker#downcallHandle} 创建对应的
    * {@link MethodHandle} 下行调用句柄，分别缓存到 {@link #searchMftHandle} 与 {@link #cancelHandle}。
    *
    * @throws IllegalStateException 若原生库尚未加载，或任一必需符号不存在
    * @author CH
    * @since 4.0.0.42
     */
    private static void bindFunctions() {
        if (LIBRARY == null || ARENA == null) {
            throw new IllegalStateException("Native library not loaded");
        }
        Linker linker = Linker.nativeLinker();

        MemorySegment searchSym = LIBRARY.find("fast_search_mft")
                .orElseThrow(() -> new RuntimeException("fast_search_mft not found"));
        searchMftHandle = linker.downcallHandle(searchSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));

        MemorySegment cancelSym = LIBRARY.find("fast_search_cancel")
                .orElseThrow(() -> new RuntimeException("fast_search_cancel not found"));
        cancelHandle = linker.downcallHandle(cancelSym,
                FunctionDescriptor.ofVoid());
    }

    /**
    * 在指定根目录下执行 NTFS MFT 直读搜索（仅 窗口 有效）。
    *
    * <p>该方法通过 FFM 下行调用原生 {@code fast_search_mft} 函数，直接读取 NTFS 主文件表（MFT）
    * 实现高速文件名匹配搜索（通常需要管理员权限）。搜索过程中每命中一条结果，原生层会通过
    * upcall 回调将文件路径与长度回传，再由 {@link #upcallCallback} 转发到调用方提供的
    * {@code callback}。调用结束后会在受限 Arena 中自动释放所有临时内存。
    *
    * @param rootDir    搜索的根目录路径（UTF-8 字符串，会按原生约定编码后传入）
    * @param pattern    glob 风格的文件名匹配模式（如 {@code *.log}、{@code app?.txt}）
    * @param maxResults 单次搜索返回的最大结果数量；传 {@code 0} 或负数通常表示不限制
    * @param callback   命中结果回调，接收参数为「文件路径字符串」与「路径字节长度」，不允许为 {@code null}
    * @return 实际命中并返回的结果数量；调用失败时返回 {@code -99}
    * @throws IllegalStateException 若原生库尚未加载（{@link #isLoaded()} 为 {@code false}）
    * @author CH
    * @since 4.0.0.42
     */
    public static int searchMft(String rootDir, String pattern,
                                int maxResults, BiConsumer<String, Long> callback) {
        checkLoaded();

        try (var arena = Arena.ofConfined()) {
            MemorySegment rootSeg = arena.allocateFrom(rootDir, StandardCharsets.UTF_8);
            MemorySegment patSeg = arena.allocateFrom(pattern, StandardCharsets.UTF_8);

            MemorySegment stub = createCallbackStub(callback);

            int count = (int) searchMftHandle.invokeExact(
                    (MemorySegment) rootSeg,
                    (MemorySegment) patSeg,
                    maxResults,
                    (MemorySegment) stub);

            return count;
        } catch (Throwable e) {
            log.error("searchMft call failed: {}", e.getMessage(), e);
            return -99;
        }
    }

    /**
    * 取消正在进行的 MFT 搜索。
    *
    * <p>通过 FFM 下行调用原生 {@code fast_search_cancel} 函数请求中断当前搜索任务。
    * 若原生库尚未加载则直接返回，不做任何操作；调用过程中出现的异常仅记录日志，不影响调用方。
    *
    * @author CH
    * @since 4.0.0.42
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
    * 判断快速文件搜索原生库是否已成功加载。
    *
    * <p>该标志在 {@link #loadLibrary()} 成功绑定函数句柄后由 {@code false} 置为 {@code true}；
    * 当原生库缺失或加载/绑定失败时保持 {@code false}。调用方在调用 {@link #searchMft} 前应
    * 先检查此方法，以避免触发 {@link IllegalStateException}。
    *
    * @return 原生库已加载且可用返回 {@code true}，否则返回 {@code false}
    * @author CH
    * @since 4.0.0.42
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 校验加载 */
    private static void checkLoaded() {
        if (!loaded) {
            throw new IllegalStateException(
                    "Fast file search native library not loaded. Check fast_file_search.dll in classpath:/native/ or java.library.path.");
        }
    }

    /**
    * 创建callbackstub
    *
    * @param consumer consumer
    * @return 创建callbackstub的结果
     */
    private static MemorySegment createCallbackStub(BiConsumer<String, Long> consumer) {
        try {
            var lookup = MethodHandles.lookup();
            var handle = lookup.findStatic(
                    FastFileSearchNativeBridge.class, "upcallCallback",
                    MethodType.methodType(void.class, MemorySegment.class, long.class, BiConsumer.class));
            var boundHandle = MethodHandles.insertArguments(handle, 2, consumer);
            return LINKER.upcallStub(boundHandle,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                    ARENA);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create upcall stub", e);
        }
    }

    /**
    * upcallcallback
    *
    * @param pathPtr 路径ptr
    * @param length 长度
    * @param consumer consumer
     */
    private static void upcallCallback(MemorySegment pathPtr, long length, BiConsumer<String, Long> consumer) {
        if (pathPtr.equals(MemorySegment.NULL)) { return; }
        String path = pathPtr.reinterpret(length).getString(0, StandardCharsets.UTF_8);
        consumer.accept(path, length);
    }
}
