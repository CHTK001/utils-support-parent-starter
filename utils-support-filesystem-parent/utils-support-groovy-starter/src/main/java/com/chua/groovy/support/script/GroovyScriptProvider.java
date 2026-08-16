package com.chua.groovy.support.script;

import com.chua.common.support.lang.script.marker.listener.FileScriptListener;
import com.chua.common.support.lang.script.marker.listener.Listener;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.script.ScriptProvider;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groovy 脚本执行提供器，基于 GroovyShell 动态加载并执行脚本文件。
 * <p>SPI 类型：{@code groovy}。context 中的 Map 条目会逐个暴露为 Groovy 绑定变量。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("groovy")
public class GroovyScriptProvider implements ScriptProvider {

    /**
     * 脚本路径 -> FileScriptListener 缓存
     */
    private final Map<Path, Listener> listenerCache = new ConcurrentHashMap<>();

    /**
     * @return 引擎名称 {@code groovy}
     */
    @Override
    public String engineName() {
        return "groovy";
    }

    /**
     * 预加载并缓存脚本监听器。
     *
     * @param scriptPath 脚本路径
     * @return true 表示加载成功
     */
    @Override
    public boolean loadScript(Path scriptPath) {
        try {
            Listener listener = new FileScriptListener(scriptPath);
            listenerCache.put(scriptPath, listener);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行脚本：context 中的 Map 条目会逐个暴露为 Groovy 绑定变量（{@code context} 也作为变量注入）。
     *
     * @param scriptPath 脚本路径
     * @param context    绑定上下文（可为 Map 或其他对象）
     * @return 脚本求值结果
     */
    @Override
    public Object executeScript(Path scriptPath, Object context) {
        Listener listener = listenerCache.computeIfAbsent(scriptPath, FileScriptListener::new);
        String source = listener.getSource();
        if (source == null || source.isEmpty()) {
            throw new IllegalStateException("脚本源码为空: " + scriptPath);
        }

        Binding binding = new Binding();
        if (context != null) {
            binding.setProperty("context", context);
            if (context instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) context).entrySet()) {
                    if (entry.getKey() instanceof String) {
                        binding.setProperty((String) entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        GroovyShell shell = new GroovyShell(binding);
        return shell.evaluate(source);
    }

    /**
     * 卸载脚本（移除监听器缓存）。
     *
     * @param scriptPath 脚本路径
     */
    @Override
    public void unloadScript(Path scriptPath) {
        listenerCache.remove(scriptPath);
    }

    /**
     * 判断脚本是否已加载。
     *
     * @param scriptPath 脚本路径
     * @return true 表示已加载
     */
    @Override
    public boolean isLoaded(Path scriptPath) {
        return listenerCache.containsKey(scriptPath);
    }
}
