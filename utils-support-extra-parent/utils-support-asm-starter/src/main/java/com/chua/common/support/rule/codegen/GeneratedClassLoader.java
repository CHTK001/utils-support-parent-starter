package com.chua.common.support.rule.codegen;

import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字节码类加载器。
 *
 * <p>把 ASM 生成的类定义进 JVM。每个编译产物一个独立加载器实例，
 * 相互隔离；同时缓存已定义的类，避免同一表达式被反复编译时重复定义。</p>
 *
 * <h2>为什么不用 ClassLoader#defineClass 直接挂在父加载器上</h2>
 * <p>规则表达式会被热更新反复编译，类名必须唯一，否则会抛
 * {@code LinkageError}（同名类已定义）。本类用
 * {@code defineClass(name, bytes, 0, len, domain)} 指定独立的
 * 运行域，使同名类也能共存，从而允许缓存键只用表达式文本。</p>
 *
 * <h2>生命周期</h2>
 * <p>加载器持有生成的类；只要还有条件实例存活，加载器就不会被回收。
 * 使用方无需显式释放——规则引擎的规则集本身不可变，
 * 旧规则集被换出后其条件实例随之成为垃圾，整批生成类随之回收。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class GeneratedClassLoader extends ClassLoader {

    /**
     * 生成类的运行域，继承本类的运行域
     */
    private static final ProtectionDomain DOMAIN =
            GeneratedClassLoader.class.getProtectionDomain();

    /**
     * 已定义的类
     */
    private final Map<String, Class<?>> defined = new ConcurrentHashMap<>();

    /**
     * 创建类加载器。
     *
     * @param parent 父加载器
     */
    public GeneratedClassLoader(ClassLoader parent) {
        super("rule-codegen", parent);
    }

    /**
     * 定义一个类。
     *
     * <p>优先走内部缓存；未命中时用独立运行域定义，
     * 因此同名类可重复定义而不会冲突。</p>
     *
     * @param name  二进制名，如 {@code a.b.C}
     * @param bytes 字节码
     * @return 生成的类
     */
    public Class<?> define(String name, byte[] bytes) {
        Class<?> cached = defined.get(name);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = defined.get(name);
            if (cached == null) {
                Class<?> created = defineClass(name, bytes, 0, bytes.length, DOMAIN);
                defined.put(name, created);
                return created;
            }
            return cached;
        }
    }

    /**
     * 获取已定义类数量。
     *
     * @return 数量
     */
    public int definedCount() {
        return defined.size();
    }
}
