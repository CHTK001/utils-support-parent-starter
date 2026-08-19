package com.chua.filesystem.log.support.bridge;


import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FFM 原生函数注册表 - 缓存 MethodHandle 避免重复 downcall 开销
 * <p>
 * 基于 Java 25 FFM API，在运行时动态绑定系统库函数。
 * 每个平台维护独立的 SymbolLookup 和 MethodHandle 缓存。
 * </p>
 *
 * @since 4.0.0.42
 */
public final class NativeFunctionRegistry {

    /** Linker */
    private final Linker linker;
    /** Lookup */
    private final SymbolLookup lookup;
    /** handleCache */
    private final Map<String, MethodHandle> handleCache;

    public NativeFunctionRegistry(SymbolLookup lookup) {
        this.linker = Linker.nativeLinker();
        this.lookup = lookup;
        this.handleCache = new ConcurrentHashMap<>();
    }

    /**
     * 使用库名加载系统库创建注册表
     */
    public static NativeFunctionRegistry ofLibrary(String libraryName) {
        try {
            String mapped = mapLibraryName(libraryName);
            NativeFunctionRegistry reg = tryLoad(mapped);
            if (reg != null) {
                return reg;
            }
            reg = tryLoadViaJvm(libraryName);
            return reg;
        } catch (Exception e) {
            return null;
        }
    }

    private static NativeFunctionRegistry tryLoad(String libName) {
        NativeFunctionRegistry reg = tryCreate(libName);
        if (reg != null) {
            return reg;
        }
        if (PlatformSystems.isLinux()) {
            int dot = libName.lastIndexOf('.');
            String base = (dot > 0) ? libName.substring(0, dot) : libName;
            reg = tryCreate(base + ".so.0");
            if (reg != null) {
                return reg;
            }
            reg = tryCreate(base + ".so.1");
            if (reg != null) {
                return reg;
            }
        }
        return null;
    }

    private static NativeFunctionRegistry tryCreate(String fullName) {
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(
                    fullName,
                    java.lang.foreign.Arena.global()
            );
            return new NativeFunctionRegistry(lookup);
        } catch (Exception e) {
            return null;
        }
    }

    private static NativeFunctionRegistry tryLoadViaJvm(String libraryName) {
        try {
            System.loadLibrary(libraryName);
            SymbolLookup lookup = SymbolLookup.loaderLookup();
            return new NativeFunctionRegistry(lookup);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String mapLibraryName(String libraryName) {
        if (PlatformSystems.isLinux()) {
            if (!libraryName.endsWith(".so") && !libraryName.contains("/")) {
                return "lib" + libraryName + ".so";
            }
        }
        return libraryName;
    }

    public MethodHandle register(String name, FunctionDescriptor descriptor) {
        return handleCache.computeIfAbsent(key(name, descriptor), k -> {
            var symbol = lookup.find(name);
            if (symbol.isEmpty()) {
                return null;
            }
            return linker.downcallHandle(symbol.get(), descriptor);
        });
    }

    public MethodHandle require(String name, FunctionDescriptor descriptor) {
        MethodHandle handle = register(name, descriptor);
        if (handle == null) {
            throw new UnsatisfiedLinkError(
                    "Symbol not found: " + name + " in " + lookup
            );
        }
        return handle;
    }

    public boolean hasSymbol(String name) {
        return lookup.find(name).isPresent();
    }

    public SymbolLookup getSymbolLookup() {
        return lookup;
    }

    public Linker getLinker() {
        return linker;
    }

    private static String key(String name, FunctionDescriptor descriptor) {
        return name + "@" + descriptor.toMethodType().toMethodDescriptorString();
    }
}
