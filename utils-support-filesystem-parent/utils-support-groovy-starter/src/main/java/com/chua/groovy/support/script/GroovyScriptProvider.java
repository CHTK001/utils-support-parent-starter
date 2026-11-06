package com.chua.groovy.support.script;

import com.chua.common.support.lang.script.marker.listener.FileScriptListener;
import com.chua.common.support.lang.script.marker.listener.Listener;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.script.ScriptHost;
import com.chua.common.support.task.script.ScriptProvider;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.InvokerHelper;

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
 *   <li>加载时把源码预编译成脚本类并缓存（Path -> Class），执行时复用同一编译产物、
 *   每次新建 {@link Script} 实例。编译只做一次，同时避免复用带执行期状态的 Script 实例
 *   带来的并发问题</li>
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
     * 脚本路径 -> 编译后的脚本类 缓存（Path -> Class）
     */
    private final Map<Path, Class<? extends Script>> classCache = new ConcurrentHashMap<>();

    /**
     * 复用的 groovyshell，负责把源码预编译为脚本类
     */
    private final GroovyShell shell = new GroovyShell(new Binding());

    /**
     * 上下文中脚本宿主的键（与 {@code JavaScriptProvider} 同名，两个引擎共用一套上下文约定）
     */
    private static final String SCRIPT_HOST_KEY = "scriptHost";

    /**
     * 获取引擎名称。
     *
     * @return 引擎名称 {@code groovy}
     */
    @Override
    public String engineName() {
        return "groovy";
    }

    /**
     * 预编译并缓存脚本类。
     *
     * <p>读取源码后用复用的 {@link GroovyShell} 解析成脚本类并缓存；执行阶段直接复用该
     * 编译产物，不再重复编译。编译失败抛出 {@link IllegalStateException}，便于「保存时校验」。</p>
     *
     * @param scriptPath 脚本路径
     * @return true 表示加载成功
     */
    @Override
    public boolean loadScript(Path scriptPath) {
        if (scriptPath == null) {
            throw new IllegalArgumentException("Groovy 脚本路径不能为 null");
        }
        if (classCache.containsKey(scriptPath)) {
            return true;
        }
        Listener listener = listenerCache.computeIfAbsent(scriptPath, FileScriptListener::new);
        String source = listener.getSource();
        if (source == null || source.isEmpty()) {
            throw new IllegalStateException("脚本源码为空: " + scriptPath);
        }
        try {
            classCache.put(scriptPath, parseScript(source));
            return true;
        } catch (RuntimeException e) {
            // Groovy 的 CompilationFailedException 等运行时异常原样透出
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Groovy 脚本编译失败: " + scriptPath + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 把源码预编译为脚本类。
     *
     * <p>走复用 {@link GroovyShell} 的 GroovyClassLoader，编译结果可在后续多次执行中复用。</p>
     *
     * @param source 脚本源码
     * @return 脚本类
     */
    @SuppressWarnings("unchecked")
    private Class<? extends Script> parseScript(String source) {
        return (Class<? extends Script>) shell.getClassLoader().parseClass(source);
    }

    /**
     * 执行脚本：上下文 中的 映射 条目会逐个暴露为 Groovy 绑定变量（{@code context} 也作为变量注入）。
     *
     * <p>复用加载阶段缓存好的脚本类，每次执行新建 {@link Script} 实例并运行，
     * 编译只做一次。Script 实例带执行期状态，不复用，保证并发安全。</p>
     *
     * <p>宿主装配：上下文里带 {@link ScriptHost} 时，脚本实例创建后立即做一次字段装配，
     * 使脚本类上声明的 {@code @Autowired} 生效（与 {@code JavaScriptProvider} 的行为对齐）。
     * 脚本内部 {@code new} 出来的对象不会自动装配，需要在脚本里显式调用
     * {@code scriptHost.inject(对象)} 或直接用 {@code objectContext.getBean(...)} 取 Bean。</p>
     *
     * @param scriptPath 脚本路径
     * @param context    绑定上下文（可为 映射 或其他对象）
     * @return 脚本求值结果
     */
    @Override
    public Object executeScript(Path scriptPath, Object context) {
        Class<? extends Script> scriptClass = classCache.get(scriptPath);
        if (scriptClass == null) {
            loadScript(scriptPath);
            scriptClass = classCache.get(scriptPath);
        }
        if (scriptClass == null) {
            throw new IllegalStateException("Groovy 脚本未加载: " + scriptPath);
        }

        // 每次执行使用独立 Binding，避免复用 Script 实例的执行期状态
        Binding binding = new Binding();
        binding.setVariable("context", context);
        if (context instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) context).entrySet()) {
                if (entry.getKey() instanceof String key) {
                    binding.setVariable(key, entry.getValue());
                }
            }
        }

        Script script = InvokerHelper.createScript(scriptClass, binding);
        injectHost(script, context);
        return script.run();
    }

    /**
     * 若上下文携带脚本宿主，则对脚本实例做一次字段装配。
     *
     * <p>Spring 宿主下这一步让脚本类字段的 {@code @Autowired} 生效；非 Spring 宿主可不提供。
     * 装配失败不抛出：宿主能力是增强项，不该把脚本执行带崩。</p>
     *
     * @param instance 脚本实例
     * @param context  执行上下文
     */
    private static void injectHost(Object instance, Object context) {
        if (!(context instanceof Map)) {
            return;
        }
        Object host = ((Map<?, ?>) context).get(SCRIPT_HOST_KEY);
        if (host instanceof ScriptHost) {
            ((ScriptHost) host).inject(instance);
        }
    }

    /**
     * 卸载脚本（移除监听器、脚本类缓存并清理 GroovyClassLoader 缓存）。
     *
     * <p>卸载时主动清理 Shell 内部 GroovyClassLoader 的缓存，
     * 释放 源缓存 和 类信息 反射缓存，帮助 Metaspace 内存回收。</p>
     *
     * @param scriptPath 脚本路径
     */
    @Override
    public void unloadScript(Path scriptPath) {
        classCache.remove(scriptPath);
        listenerCache.remove(scriptPath);
        try {
            shell.getClassLoader().clearCache();
        } catch (Exception ignored) {
            // 清理失败时静默忽略
        }
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
