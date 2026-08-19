package com.chua.filesystem.log.support.bridge;

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
import java.nio.file.StandardCopyOption;
import java.util.function.BiConsumer;

/**
 * 快速文件搜索原生库桥接 - Java 25 FFM (Panama) 绑定
 * <p>
 * Windows NTFS MFT 直读搜索（需要管理员权限）。
 * </p>
 *
 * @since 4.0.0.42
 */
@Slf4j
public final class FastFileSearchNativeBridge {

    /** Lib_name_windows */
    private static final String LIB_NAME_WINDOWS = "fast_file_search.dll";
    /** Lib_base_name */
    private static final String LIB_BASE_NAME = "fast_file_search";

    /** Arena */
    private static Arena ARENA;
    /** Library */
    private static SymbolLookup LIBRARY;

    /** SearchMFThandle */
    private static MethodHandle searchMftHandle;
    /** Cancelhandle */
    private static MethodHandle cancelHandle;

    private static volatile boolean loaded = false;
    /** Load_lock */
    private static final Object LOAD_LOCK = new Object();

    /** Linker */
    private static final Linker LINKER = Linker.nativeLinker();

    static {
        loadLibrary();
    }

    private FastFileSearchNativeBridge() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void loadLibrary() {
        if (loaded) {
            return;
        }
        synchronized (LOAD_LOCK) {
            if (loaded) {
                return;
            }

            String libName = getPlatformLibName();
            log.debug("Loading fast file search native library: {}", libName);

            boolean libLoaded = false;
            if (!libLoaded) {
                libLoaded = loadFromClasspath(libName);
            }
            if (!libLoaded) {
                libLoaded = loadFromSystemPath();
            }
            if (!libLoaded) {
                libLoaded = loadFromSystemLookup(libName);
            }

            if (!libLoaded) {
                log.warn("Failed to load fast file search native library: {}. MFT search unavailable.", libName);
                return;
            }

            try {
                bindFunctions();
                loaded = true;
                log.info("Fast file search native library loaded successfully: {}", libName);
            } catch (Throwable t) {
                log.error("Function binding failed: {}", t.getMessage(), t);
                loaded = false;
            }
        }
    }

    private static boolean loadFromClasspath(String libName) {
        try {
            var is = FastFileSearchNativeBridge.class.getClassLoader()
                    .getResourceAsStream("native/" + libName);
            if (is == null) {
                return false;
            }

            Path tempFile = Files.createTempFile("fast_file_search_", ".dll");
            tempFile.toFile().deleteOnExit();

            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            is.close();

            System.load(tempFile.toAbsolutePath().toString());

            ARENA = Arena.ofShared();
            LIBRARY = SymbolLookup.libraryLookup(tempFile, ARENA);
            return true;
        } catch (Throwable e) {
            log.debug("classpath load failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean loadFromSystemPath() {
        try {
            System.loadLibrary(LIB_BASE_NAME);
            ARENA = Arena.ofShared();
            LIBRARY = SymbolLookup.libraryLookup(LIB_NAME_WINDOWS, ARENA);
            return true;
        } catch (Throwable e) {
            log.debug("java.library.path load failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean loadFromSystemLookup(String libName) {
        try {
            ARENA = Arena.ofShared();
            LIBRARY = SymbolLookup.libraryLookup(libName, ARENA);
            LIBRARY.find("fast_search_mft")
                    .orElseThrow(() -> new RuntimeException("fast_search_mft not found"));
            return true;
        } catch (Throwable e) {
            log.debug("System path load failed: {}", e.getMessage());
            return false;
        }
    }

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

    public static boolean isLoaded() {
        return loaded;
    }

    private static void checkLoaded() {
        if (!loaded) {
            throw new IllegalStateException(
                    "Fast file search native library not loaded. Check fast_file_search.dll in classpath:/native/ or java.library.path.");
        }
    }

    private static String getPlatformLibName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) { return LIB_NAME_WINDOWS; }
        return LIB_NAME_WINDOWS;
    }

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

    private static void upcallCallback(MemorySegment pathPtr, long length, BiConsumer<String, Long> consumer) {
        if (pathPtr.equals(MemorySegment.NULL)) { return; }
        String path = pathPtr.reinterpret(length).getString(0, StandardCharsets.UTF_8);
        consumer.accept(path, length);
    }
}
