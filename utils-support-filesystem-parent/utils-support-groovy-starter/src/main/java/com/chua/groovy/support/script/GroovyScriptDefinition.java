package com.chua.groovy.support.script;

import com.chua.common.support.lang.script.marker.ScriptMarker;
import com.chua.common.support.lang.script.marker.listener.FileScriptListener;
import com.chua.common.support.lang.script.marker.listener.Listener;
import com.chua.common.support.lang.script.marker.listener.UrlScriptListener;
import com.chua.common.support.objects.definition.AbstractScriptDefinition;
import com.chua.common.support.spi.annotations.Spi;

import java.net.URL;
import java.nio.file.Path;

/**
 * Groovy 脚本定义。
 *
 * <p>通过 {@link ScriptDefinition} SPI 机制注册，提供基于文件或 URL 的
 * Groovy 脚本热重载能力。内部组合 {@link GroovyScriptMarker} 和
 * {@link FileScriptListener}/{@link UrlScriptListener}。</p>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 文件监听
 * ScriptDefinition def = GroovyScriptDefinition.of("myScript", Path.of("transform.groovy"));
 *
 * // URL 轮询
 * ScriptDefinition def = GroovyScriptDefinition.of("remoteScript", new URL("http://host/script.groovy"), 5000);
 * }</pre>0);
 * }</pre></p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see AbstractScriptDefinition
 * @see ScriptDefinition
 */
@Spi("groovy")
public class GroovyScriptDefinition extends AbstractScriptDefinition {

    /**
     * 构造空的 Groovy 脚本定义。
     */
    public GroovyScriptDefinition() {
    }

    /**
     * 构造基于文件的 Groovy 脚本定义。
     *
     * @param name      Bean 名称
     * @param scriptPath 脚本文件路径
     */
    public GroovyScriptDefinition(String name, Path scriptPath) {
        setName(name);
        setListener(new FileScriptListener(scriptPath));
        setScriptMarker(new GroovyScriptMarker());
    }

    /**
     * 构造基于 URL 的 Groovy 脚本定义。
     *
     * @param name          Bean 名称
     * @param scriptUrl     脚本内容 URL
     * @param periodMillis  轮询周期，单位毫秒
     */
    public GroovyScriptDefinition(String name, URL scriptUrl, long periodMillis) {
        setName(name);
        setListener(new UrlScriptListener(scriptUrl, periodMillis));
        setScriptMarker(new GroovyScriptMarker());
    }

    /**
     * 创建基于文件的 Groovy 脚本定义。
     *
     * @param name      Bean 名称
     * @param scriptPath 脚本文件路径
     * @return GroovyScriptDefinition 实例
     */
    public static GroovyScriptDefinition of(String name, Path scriptPath) {
        return new GroovyScriptDefinition(name, scriptPath);
    }

    /**
     * 创建基于 URL 的 Groovy 脚本定义。
     *
     * @param name          Bean 名称
     * @param scriptUrl     脚本内容 URL
     * @param periodMillis  轮询周期，单位毫秒
     * @return GroovyScriptDefinition 实例
     */
    public static GroovyScriptDefinition of(String name, URL scriptUrl, long periodMillis) {
        return new GroovyScriptDefinition(name, scriptUrl, periodMillis);
    }
}
