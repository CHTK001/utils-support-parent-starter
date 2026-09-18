package com.chua.groovy.support.script;

import com.chua.common.support.lang.script.marker.listener.Listener;
import com.chua.common.support.lang.script.marker.AbstractScriptMarker;
import com.chua.common.support.lang.script.marker.ScriptMarker;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.ObjectUtils;
import groovy.lang.GroovyClassLoader;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.io.IOException;

/**
* Groovy 脚本标记器实现。
*
* <p>通过 {@link ScriptMarker} SPI 机制注册为 {@code "groovy"} 脚本引擎，
* 支持将 Groovy 源代码动态编译为 Java 类 并创建实例对象。</p>
*
* <h3>工作原理</h3>
* <ol>
*   <li>从 {@link Listener} 获取 Groovy 源代码</li>
*   <li>使用 {@link GroovyClassLoader} 动态编译源码</li>
*   <li>使用指定的构造参数创建对象实例</li>
*   <li>通过 {@link #getScriptClassLoader()} 暴露最后一次使用的 ClassLoader</li>
* </ol>
*
* <h3>类加载器生命周期</h3>
* <ul>
*   <li>若传入的 classLoader 为 {@link GroovyClassLoader} 实例，则复用且不关闭</li>
*   <li>若为其他 ClassLoader 或 null，则新建 GroovyClassLoader，失败时自动关闭</li>
*   <li>热重载时由 {@link com.chua.common.support.objects.definition.AbstractScriptDefinition}
* 负责销毁旧的 类加载</li>
*   <li>通过 {@link #previousClassLoader} 追踪旧 ClassLoader，支持主动清理其 Groovy 缓存</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
* @see AbstractScriptMarker
* @see Listener
* @see GroovyClassLoader
 */
@Spi("groovy")
@Slf4j
public class GroovyScriptMarker extends AbstractScriptMarker {

    /**
    * 默认 Groovy 编译器配置（UTF-8 编码）。
    * <p>每个实例使用独立的 CompilerConfiguration 副本，避免多脚本共享配置导致缓存污染。</p>
    */
    private static final CompilerConfiguration DEFAULT_CONFIG = new CompilerConfiguration();

    static {
        DEFAULT_CONFIG.setSourceEncoding("UTF-8");
    }

    /**
    * Groovy 编译器配置
    */
    private final CompilerConfiguration config;

    /**
    * 最后一次编译生成的 类 类型
    */
    private Class<?> compiledClass;

    /**
    * 最后一次编译脚本时使用的类加载器
    */
    private ClassLoader lastClassLoader;

    /**
    * 上一次使用的类加载器，用于在热重载时主动清理其 Groovy 内部缓存。
    * <p>当新的 GroovyClassLoader 被创建后，旧的 ClassLoader 被保存到此字段，
    * 供 {@link #cleanupPreviousClassLoader()} 调用 clear缓存() 释放 Metaspace。</p>
    */
    private volatile ClassLoader previousClassLoader;

    /**
    * 构造 Groovy 脚本标记器，使用默认编译器配置。
    * <p>每个实例创建独立的 CompilerConfiguration 副本，避免共享配置导致的缓存污染。</p>
    */
    public GroovyScriptMarker() {
        this.config = new CompilerConfiguration(DEFAULT_CONFIG);
    }

    /**
    * 构造 Groovy 脚本标记器。
    *
    * @param config 编译器配置，为 空 时使用默认配置
    */
    public GroovyScriptMarker(CompilerConfiguration config) {
        this.config = config != null ? new CompilerConfiguration(config) : new CompilerConfiguration(DEFAULT_CONFIG);
    }

    @Override
    /**
    * 创建脚本对象实例（线程安全）。
    *
    * <p>若传入的 classLoader 是 {@link GroovyClassLoader} 则复用；
    * 否则新建 groovy类加载。每次创建新 类加载 时，
    * 旧的 类加载 被保存到 {@link #previousClassLoader} 供后续清理。</p>
    */
    public synchronized Object createObject(Listener listener, ClassLoader classLoader, Object[] args) {
        if (listener == null) {
            return null;
        }

        boolean reuseClassLoader = classLoader instanceof GroovyClassLoader;
        GroovyClassLoader groovyClassLoader = reuseClassLoader
                ? (GroovyClassLoader) classLoader
                : new GroovyClassLoader(
                ObjectUtils.defaultIfNull(classLoader, ClassUtils.getDefaultClassLoader()),
                config);

        try {
            this.compiledClass = groovyClassLoader.parseClass(listener.getSource());

 // 新增：保存旧 类加载 引用，供后续清理 Groovy 内部缓存
            if (this.lastClassLoader != null && this.lastClassLoader != groovyClassLoader) {
                this.previousClassLoader = this.lastClassLoader;
            }

            this.lastClassLoader = groovyClassLoader;
            return ClassUtils.forObject(compiledClass, args);
        } catch (CompilationFailedException e) {
            log.error("[GroovyScriptMarker] Groovy 脚本编译失败", e);
            if (!reuseClassLoader) {
                closeSilently(groovyClassLoader);
            }
            return null;
        } catch (Exception e) {
            log.error("[GroovyScriptMarker] Groovy 脚本处理异常", e);
            if (!reuseClassLoader) {
                closeSilently(groovyClassLoader);
            }
            return null;
        }
    }

    @Override
    public Class<?> getType() {
        return compiledClass;
    }

    @Override
    /** 获取script类加载 */
    public ClassLoader getScriptClassLoader() {
        return lastClassLoader;
    }

    /**
    * 获取编译器配置。
    *
    * @return 编译器配置实例
    */
    public CompilerConfiguration getCompilerConfiguration() {
        return config;
    }

    /**
    * 清理旧 类加载 的 Groovy 内部缓存。
    *
    * <p>在热重载后调用，释放旧 {@link GroovyClassLoader} 的 sourceCache 和
    * 类信息 反射缓存，帮助 Metaspace 内存回收。</p>
    *
    * <p>仅在 previousClassLoader 是 GroovyClassLoader 实例时执行清理。</p>
    */
    public void cleanupPreviousClassLoader() {
        ClassLoader prev = this.previousClassLoader;
        if (prev instanceof GroovyClassLoader groovyCL) {
            if (log.isDebugEnabled()) {
                log.debug("[GroovyScriptMarker] 清理旧 ClassLoader 的 Groovy 缓存: hash={}",
                        System.identityHashCode(prev));
            }
            try {
                groovyCL.clearCache();
            } catch (Exception e) {
                log.warn("[GroovyScriptMarker] 清理旧 ClassLoader 缓存失败", e);
            }
        }
        this.previousClassLoader = null;
    }

    /**
    * 静默关闭 groovy类加载，忽略异常。
    *
    * <p>关闭前先调用 {@code clearCache()} 清理 Groovy 内部缓存。</p>
    *
    * @param loader 待关闭的类加载器
    */
    private static void closeSilently(GroovyClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.clearCache();
            loader.close();
        } catch (IOException e) {
            log.warn("[GroovyScriptMarker] 关闭 GroovyClassLoader 失败", e);
        } catch (Exception e) {
            log.warn("[GroovyScriptMarker] 关闭 GroovyClassLoader 异常", e);
        }
    }
}
