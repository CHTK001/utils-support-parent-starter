package com.chua.groovy.support.script;

import com.chua.common.support.lang.script.marker.listener.FileScriptListener;
import com.chua.common.support.lang.script.marker.listener.Listener;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.script.ScriptProvider;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* Groovy 脚本执行提供器，基于 groovyshell 动态加载并执行脚本文件。
*
* <p>SPI 类型：{@code groovy}。context 中的 Map 条目会逐个暴露为 Groovy 绑定变量。</p>
*
* <p>优化说明：
* <ul>
*   <li>复用 {@link GroovyShell} 实例，避免每次执行创建新 Shell 导致的 ClassLoader 泄漏</li>
*   <li>卸载脚本时主动清理 Shell 内部的 GroovyClassLoader 缓存</li>
* </ul></p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("groovy")
public class GroovyScriptProvider implements ScriptProvider {

    /**
    * 脚本路径 -> 文件script监听器 缓存
    */
    private final Map<Path, Listener> listenerCache = new ConcurrentHashMap<>();

    /**
    * 脚本路径 -> groovyshell 缓存，复用 Shell 避免每次创建新 类加载
    */
    private final Map<Path, GroovyShell> shellCache = new ConcurrentHashMap<>();

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
    * 执行脚本：上下文 中的 映射 条目会逐个暴露为 Groovy 绑定变量（{@code context} 也作为变量注入）。
    *
    * <p>复用已有的 {@link GroovyShell} 实例，避免每次执行创建新 Shell 导致的 ClassLoader 泄漏。
    * Shell 的 Binding 是可变的，每次执行前更新绑定变量。</p>
    *
    * @param scriptPath 脚本路径
    * @param context    绑定上下文（可为 映射 或其他对象）
    * @return 脚本求值结果
    */
    @Override
    public Object executeScript(Path scriptPath, Object context) {
        Listener listener = listenerCache.computeIfAbsent(scriptPath, FileScriptListener::new);
        String source = listener.getSource();
        if (source == null || source.isEmpty()) {
            throw new IllegalStateException("脚本源码为空: " + scriptPath);
        }

 // 复用 Shell（内部复用 类加载）
        GroovyShell shell = shellCache.computeIfAbsent(scriptPath, k -> {
            Binding binding = new Binding();
            return new GroovyShell(binding);
        });

        // 更新绑定变量（Shell 的 Binding 是可变的）
        Binding binding = shell.getContext();
        binding.setVariable("context", context);
        if (context instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) context).entrySet()) {
                if (entry.getKey() instanceof String key) {
                    binding.setVariable(key, entry.getValue());
                }
            }
        }

        return shell.evaluate(source);
    }

    /**
    * 卸载脚本（移除监听器和 Shell 缓存）。
    *
    * <p>卸载时主动清理 Shell 内部 GroovyClassLoader 的缓存，
    * 释放 源缓存 和 类信息 反射缓存，帮助 Metaspace 内存回收。</p>
    *
    * @param scriptPath 脚本路径
    */
    @Override
    public void unloadScript(Path scriptPath) {
        GroovyShell shell = shellCache.remove(scriptPath);
        if (shell != null) {
            try {
                shell.getClassLoader().clearCache();
            } catch (Exception ignored) {
                // 清理失败时静默忽略
            }
        }
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
