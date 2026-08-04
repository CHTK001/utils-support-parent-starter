package com.chua.common.support.lang.script.marker;

import com.chua.common.support.lang.script.marker.listener.Listener;

import java.nio.file.Path;
import org.jspecify.annotations.NullUnmarked;

/**
 * 脚本标记器 SPI 接口。
 *
 * <p>定义脚本代码的动态编译和对象创建能力。通过 SPI 机制支持多种脚本语言
 * （如 Groovy、JavaScript、Python 等），运行时根据脚本源码动态创建对象。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>动态编译</b> — 将脚本源码字符串编译为可执行的 Java 类</li>
 *   <li><b>对象创建</b> — 根据编译结果和构造参数创建脚本对象实例</li>
 *   <li><b>类型查询</b> — 获取最后一次编译生成的类的 Class 对象</li>
 *   <li><b>类加载器暴露</b> — 暴露最后一次使用的 ClassLoader，供 ScriptDefinition 管理生命周期</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *     // 通过 SPI 获取 Groovy 脚本标记器
 *     ScriptMarker marker = ServiceProvider.of(ScriptMarker.class).getExtension("groovy");
 *
 *     // 从监听器获取脚本源码并创建对象
 *     Listener listener = ...;
 *     Object instance = marker.createObject(listener, classLoader, args);
 *     Class<?> type = marker.getType();
 *     ClassLoader scriptClassLoader = marker.getScriptClassLoader();
 * }</pre>
 *
 * <h3>实现注意事项</h3>
 * <ul>
 *   <li>实现类应通过 {@code @Spi("languageName")} 注解注册为 SPI 服务</li>
 *   <li>{@link #createObject} 应在内部完成编译、加载和实例化全流程</li>
 *   <li>{@link #getType()} 应返回最新一次编译生成的类类型</li>
 *   <li>{@link #getScriptClassLoader()} 应返回最后一次编译使用的类加载器，供 ScriptDefinition 管理生命周期</li>
 * </ul>
 *
 * @author CH
 * @since 2024/12/12
 * @see AbstractScriptMarker
 * @see Listener
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public interface ScriptMarker {

    /**
     * 获取最后一次编译脚本时使用的类加载器。
     *
     * <p>用于 {@link com.chua.common.support.objects.definition.ScriptDefinition} 在热重载时
     * 保存和销毁旧的类加载器，释放 Metaspace 内存。</p>
     *
     * @return 最后一次使用的类加载器，未编译时返回 {@code null}
     */
    default ClassLoader getScriptClassLoader() {
        return null;
    }

    /**
     * 根据监听器获取的脚本源码创建对象实例。
     *
     * <p>实现类应在此方法内完成脚本源码的编译、加载和实例化全流程。
     * 期间使用的 {@link ClassLoader} 应通过 {@link #getScriptClassLoader()} 暴露，
     * 供 {@link com.chua.common.support.objects.definition.ScriptDefinition} 管理生命周期。</p>
     *
     * @param listener    脚本源码监听器，用于获取最新源码
     * @param classLoader 父类加载器，未指定时为 {@code null}
     * @param args        构造器参数
     * @return 创建的对象实例，编译/加载失败时返回 {@code null}
     */
    Object createObject(Listener listener, ClassLoader classLoader, Object... args);

    /**
     * 获取最后一次编译生成的脚本类的 Class 对象。
     *
     * @return 最近编译的类类型，未编译时返回 {@code null}
     */
    Class<?> getType();
}
