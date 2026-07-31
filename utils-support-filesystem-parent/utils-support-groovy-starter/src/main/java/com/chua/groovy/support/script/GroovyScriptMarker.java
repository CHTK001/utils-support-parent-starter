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
 * 支持将 Groovy 源代码动态编译为 Java Class 并创建实例对象。</p>
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
 *       负责销毁旧的 ClassLoader</li>
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
     * 默认 Groovy 编译器配置（UTF-8 编码）
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
     * 最后一次编译生成的 Class 类型
     */
    private Class<?> compiledClass;

    /**
     * 最后一次编译脚本时使用的类加载器
     */
    private ClassLoader lastClassLoader;

    /**
     * 构造 Groovy 脚本标记器，使用默认编译器配置。
     */
    public GroovyScriptMarker() {
        this(DEFAULT_CONFIG);
    }

    /**
     * 构造 Groovy 脚本标记器。
     *
     * @param config 编译器配置，为 null 时使用默认配置
     */
    public GroovyScriptMarker(CompilerConfiguration config) {
        this.config = config != null ? config : DEFAULT_CONFIG;
    }

    @Override
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
     * 静默关闭 GroovyClassLoader，忽略异常。
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
