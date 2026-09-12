package com.chua.common.support.lang.script.marker;

import com.chua.common.support.lang.compile.Compiler;
import com.chua.common.support.lang.compile.JdkCompiler;
import com.chua.common.support.lang.script.marker.listener.Listener;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.ObjectUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicReference;

/**
* Java 原生脚本标记器实现。
*
* <p>通过 {@link ScriptMarker} SPI 机制注册为 {@code "java"} 脚本引擎，
* 利用 JDK {@link Compiler}（{@link JdkCompiler}）将 Java 源代码动态编译为 Class 并创建实例。</p>
*
* <h3>工作原理</h3>
* <ol>
*   <li>从 {@link Listener} 获取 Java 源代码</li>
*   <li>使用 {@link JdkCompiler} 在内存中编译源码为字节码</li>
*   <li>通过反射创建对象实例</li>
*   <li>暴露最后一次使用的 {@link JdkCompiler.DynamicClassLoader}，供 {@link com.chua.common.support.objects.definition.AbstractScriptDefinition} 管理生命周期</li>
* </ol>
*
* <h3>类加载器生命周期</h3>
* <ul>
*   <li>若传入的 classLoader 是 {@link JdkCompiler.DynamicClassLoader} 实例，则复用且不关闭</li>
*   <li>若为其他 ClassLoader 或 null，则新建 DynamicClassLoader 包装传入的 parent</li>
*   <li>热重载时由 {@link com.chua.common.support.objects.definition.AbstractScriptDefinition} 负责销毁旧的 ClassLoader</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
* @see ScriptMarker
* @see Listener
* @see JdkCompiler
 */
@Spi("java")
@Slf4j
public class JavaScriptMarker extends AbstractScriptMarker {

    /**
    * JDK 动态编译器实例
     */
    private final Compiler compiler = new JdkCompiler();

    /**
    * 最后一次编译生成的 Class 类型
     */
    private Class<?> compiledClass;

    /**
    * 最后一次编译脚本时使用的类加载器
     */
    private volatile ClassLoader lastClassLoader;

    @Override
    /** 创建Object */
    public synchronized Object createObject(Listener listener, ClassLoader classLoader, Object[] args) {
        if (listener == null) {
            return null;
        }

        String source = listener.getSource();
        if (source == null || source.isEmpty()) {
            return null;
        }

        boolean reuseClassLoader = classLoader instanceof JdkCompiler.DynamicClassLoader;
        JdkCompiler.DynamicClassLoader dynamicClassLoader = reuseClassLoader
                ? (JdkCompiler.DynamicClassLoader) classLoader
                : new JdkCompiler.DynamicClassLoader(
                ObjectUtils.defaultIfNull(classLoader, ClassUtils.getDefaultClassLoader()));

        try {
            this.compiledClass = compiler.compiler(source, dynamicClassLoader);
            this.lastClassLoader = dynamicClassLoader;
            return ClassUtils.forObject(compiledClass, args);
        } catch (Exception e) {
            log.error("[JavaScriptMarker] Java 脚本编译失败", e);
            return null;
        }
    }

    @Override
    public Class<?> getType() {
        return compiledClass;
    }

    @Override
    /** 获取ScriptClassLoader */
    public ClassLoader getScriptClassLoader() {
        return lastClassLoader;
    }
}
