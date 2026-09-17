package com.chua.common.support.task.script;

import com.chua.common.support.spi.ServiceProvider;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本流程管理器，提供脚本的统一管理能力。
 *
 * <p>整合 {@link ScriptProvider} SPI，自动发现可用的脚本引擎。</p>
 *
 * <pre>{@code
 * ScriptFlow flow = ScriptFlow.of("js");
 * flow.load(Path.of("transform.js"));
 * Object result = flow.execute(Path.of("transform.js"), Map.of("name", "test"));
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
*/
public class ScriptFlow {

    /**
    * 脚本提供者
    */
    private final ScriptProvider provider;

    /**
    * 已加载的脚本缓存
    */
    private final Map<Path, Boolean> loadedScripts = new ConcurrentHashMap<>();

    /**
    * 创建 ScriptFlow 实例
    * @param provider provider
    */
    private ScriptFlow(ScriptProvider provider) {
        this.provider = provider;
    }

    /**
    * 创建脚本流程管理器。
    *
    * @param engineName 脚本引擎名称（如 "js"、"groovy"）
    * @return ScriptFlow 实例
    */
    public static ScriptFlow of(String engineName) {
        ScriptProvider provider = ServiceProvider.of(ScriptProvider.class)
                .getExtension(engineName);
        if (provider == null) {
            throw new IllegalStateException("未找到脚本引擎: " + engineName
                    + "，请添加对应的脚本引擎依赖");
        }
        return new ScriptFlow(provider);
    }

    /**
    * 获取脚本引擎名称。
    *
    * @return 引擎名称
    */
    public String getEngineName() {
        return provider.engineName();
    }

    /**
    * 加载脚本文件。
    *
    * @param scriptPath 脚本路径
    */
    public void load(Path scriptPath) {
        provider.loadScript(scriptPath);
        loadedScripts.put(scriptPath, true);
    }

    /**
    * 卸载脚本。
    *
    * @param scriptPath 脚本路径
    */
    public void unload(Path scriptPath) {
        provider.unloadScript(scriptPath);
        loadedScripts.remove(scriptPath);
    }

    /**
    * 执行脚本，自动加载。
    *
    * @param scriptPath 脚本路径
    * @param context    上下文参数
    * @return 执行结果
    */
    public Object execute(Path scriptPath, Object context) {
        if (!loadedScripts.containsKey(scriptPath)) {
            load(scriptPath);
        }
        return provider.executeScript(scriptPath, context);
    }

    /**
    * 执行脚本并返回字符串。
    *
    * @param scriptPath 脚本路径
    * @param context    上下文参数
    * @return 字符串结果
    */
    public String run(Path scriptPath, Object context) {
        Object result = execute(scriptPath, context);
        return result != null ? String.valueOf(result) : "";
    }

    /**
    * 执行脚本（无上下文）。
    *
    * @param scriptPath 脚本路径
    * @return 执行结果
    */
    public Object execute(Path scriptPath) {
        return execute(scriptPath, null);
    }

    /**
    * 批量执行目录下的所有脚本。
    *
    * @param scriptDir 脚本目录
    * @param context   共享上下文
    * @return 脚本名 → 结果的映射
    */
    public Map<String, Object> executeBatch(Path scriptDir, Object context) {
        Map<String, Object> results = new java.util.LinkedHashMap<>();
        try (var stream = java.nio.file.Files.list(scriptDir)) {
            stream.filter(p -> p.toString().endsWith(getScriptExtension()))
                    .sorted()
                    .forEach(script -> {
                        try {
                            Object result = execute(script, context);
                            results.put(script.getFileName().toString(), result);
                        } catch (Exception e) {
                            results.put(script.getFileName().toString(), "ERROR: " + e.getMessage());
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("批量执行脚本失败: " + e.getMessage(), e);
        }
        return results;
    }

    /** 获取ScriptExtension */
    private String getScriptExtension() {
        return switch (provider.engineName()) {
            case "js" -> ".js";
            case "groovy" -> ".groovy";
            case "python" -> ".py";
            default -> ".script";
        };
    }
}
