package com.chua.filesystem.log.support.bridge;

import com.chua.common.support.utils.NativeUtils;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FFM 原生函数注册表 - 缓存 {@link MethodHandle} 避免重复 downcall 绑定开销。
 * <p>
 * 基于 Java FFM（Project Panama）API，在运行时动态绑定系统/第三方原生库导出的函数。
 * 每个注册表实例维护一份独立的 {@link SymbolLookup} 与 {@link MethodHandle} 缓存，
 * 通过 {@link #ofLibrary(String)} 借助 {@link NativeUtils} 完成跨平台原生库加载，
 * 随后即可按函数名 + 函数描述符注册并缓存下行调用句柄。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class NativeFunctionRegistry {

    /** 原生链接器（JVM 全局共享实例） */
    private final Linker linker;
    /** 符号查找表，用于按名称解析原生函数地址 */
    private final SymbolLookup lookup;
    /** 方法处理 句柄缓存，键 为「函数名@方法描述符」，避免重复 downcall 绑定 */
    private final Map<String, MethodHandle> handleCache;

    /**
     * 基于已有的符号查找表创建注册表实例。
     *
     * <p>通常不直接调用本构造器，而是通过静态工厂方法 {@link #ofLibrary(String)} 创建；
     * 当调用方已自行完成原生库加载（例如通过 {@link SymbolLookup#libraryLookup} 或
     * {@link SymbolLookup#loaderLookup}）并持有 {@link SymbolLookup} 时，可使用本构造器包装。
     *
     * @param lookup 已加载原生库的符号查找表，不允许为 {@code null}
     * @author CH
     * @since 4.0.0.42
     */
    public NativeFunctionRegistry(SymbolLookup lookup) {
        this.linker = Linker.nativeLinker();
        this.lookup = lookup;
        this.handleCache = new ConcurrentHashMap<>();
    }

    /**
     * 按库名加载原生库并创建注册表。
     *
     * <p>复用 {@link NativeUtils#load(String, String)} 完成跨平台原生库加载
      * （依次尝试 {@code System.loadLibrary}、类路径 下 {@code /native/{platform}/}、
     * 用户目录 {@code ~/.native/} 等位置，含架构回退），加载成功后通过
     * {@link SymbolLookup#loaderLookup()} 获取已装入 JVM 的原生符号查找表，并构造
     * {@link NativeFunctionRegistry} 用于缓存 {@link MethodHandle}。
     *
     * @param libraryName 原生库基础名（不含前缀与后缀，如 {@code "c"}、{@code "systemd"}）
     * @return 已绑定符号查找表的注册表实例；若所有加载方式均失败则返回 {@code null}
     * @author CH
     * @since 4.0.0.42
     */
    public static NativeFunctionRegistry ofLibrary(String libraryName) {
        try {
            NativeUtils.load(libraryName, null);
            SymbolLookup lookup = SymbolLookup.loaderLookup();
            return new NativeFunctionRegistry(lookup);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 注册并缓存一个原生函数句柄。
     *
     * <p>以「函数名 + 函数描述符」为键，在 {@link #handleCache} 中按需（computeIfAbsent）解析
     * 原生符号并通过 {@link Linker#downcallHandle} 创建 {@link MethodHandle}。若符号不存在，
     * 则返回 {@code null} 且不抛异常（区别于 {@link #require}）。同一函数只会被绑定一次，
     * 后续调用直接返回缓存的句柄，避免重复 downcall 绑定开销。
     *
     * @param name        原生函数名（如 {@code "open"}、{@code "read"}）
     * @param descriptor  函数描述符，描述参数布局与返回布局
     * @return 对应的下行调用 {@link MethodHandle}；若符号未找到则返回 {@code null}
     * @author CH
     * @since 4.0.0.42
     */
    public MethodHandle register(String name, FunctionDescriptor descriptor) {
        return handleCache.computeIfAbsent(key(name, descriptor), k -> {
            var symbol = lookup.find(name);
            if (symbol.isEmpty()) {
                return null;
            }
            return linker.downcallHandle(symbol.get(), descriptor);
        });
    }

    /**
     * 注册并强制获取一个原生函数句柄。
     *
     * <p>等价于先调用 {@link #register(String, FunctionDescriptor)}，随后校验返回结果；
     * 若符号不存在（返回 {@code null}），则抛出 {@link UnsatisfiedLinkError} 并附带
     * 函数名与当前符号查找表信息，便于定位原生库不匹配或函数名拼写错误。
     *
     * @param name        原生函数名
     * @param descriptor  函数描述符
     * @return 对应的下行调用 {@link MethodHandle}，保证非 {@code null}
     * @throws UnsatisfiedLinkError 当指定符号在原生库中不存在时
     * @author CH
     * @since 4.0.0.42
     */
    public MethodHandle require(String name, FunctionDescriptor descriptor) {
        MethodHandle handle = register(name, descriptor);
        if (handle == null) {
            throw new UnsatisfiedLinkError(
                    "Symbol not found: " + name + " in " + lookup
            );
        }
        return handle;
    }

    /**
     * 判断当前符号查找表中是否存在指定原生函数。
     *
     * @param name 待检测的原生函数名
     * @return 若存在该符号返回 {@code true}，否则返回 {@code false}
     * @author CH
     * @since 4.0.0.42
     */
    public boolean hasSymbol(String name) {
        return lookup.find(name).isPresent();
    }

    /**
     * 获取当前注册表持有的符号查找表。
     *
     * @return 用于按名称解析原生函数地址的 {@link SymbolLookup} 实例
     * @author CH
     * @since 4.0.0.42
     */
    public SymbolLookup getSymbolLookup() {
        return lookup;
    }

    /**
     * 获取当前注册表使用的原生链接器。
     *
     * @return JVM 全局共享的 {@link Linker} 实例
     * @author CH
     * @since 4.0.0.42
     */
    public Linker getLinker() {
        return linker;
    }

    /**
     * 生成函数句柄缓存的键。
     *
     * <p>由「函数名」与「函数描述符对应的方法签名」拼接而成，保证不同签名（重载）的原生函数
     * 在缓存中互不冲突。
     *
     * @param name        原生函数名
     * @param descriptor  函数描述符
     * @return 形如 {@code name@(...)descriptor} 的缓存键字符串
     */
    private static String key(String name, FunctionDescriptor descriptor) {
        return name + "@" + descriptor.toMethodType().toMethodDescriptorString();
    }
}
