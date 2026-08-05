package com.chua.common.support.task.script;

import java.nio.file.Path;

/**
 * 脚本提供者接口
 *
 * <p>定义脚本的加载、执行和卸载能力。通过 SPI 机制支持多种脚本引擎
 * （如 JavaScript/Nashorn、Groovy、Python/GraalPy 等）。
 *
 * <p>使用示例：
 * <pre>{@code
 *   ScriptProvider provider = new NashornScriptProvider();
 *   provider.loadScript(Path.of("transform.js"));
 *   Object result = provider.executeScript(Path.of("transform.js"), input);
 *   provider.unloadScript(Path.of("transform.js"));
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
public interface ScriptProvider {

    /**
     * 获取脚本引擎名称
     *
     * @return 引擎标识，如 "js"、"groovy"、"python"
     */
    String engineName();

    /**
     * 加载脚本文件
     *
     * <p>读取并编译脚本文件，准备执行。
     *
     * @param scriptPath 脚本文件路径
     * @return 是否加载成功
     */
    boolean loadScript(Path scriptPath);

    /**
     * 执行脚本
     *
     * <p>执行已加载的脚本，传入上下文参数。
     *
     * @param scriptPath 脚本文件路径
     * @param context    上下文参数（可为 null）
     * @return 脚本执行结果
     */
    Object executeScript(Path scriptPath, Object context);

    /**
     * 执行脚本并返回字符串结果
     *
     * @param scriptPath 脚本文件路径
     * @param context    上下文参数
     * @return 字符串结果
     */
    default String executeScriptAsString(Path scriptPath, Object context) {
        Object result = executeScript(scriptPath, context);
        return result != null ? String.valueOf(result) : "";
    }

    /**
     * 卸载脚本
     *
     * <p>释放脚本占用的资源。
     *
     * @param scriptPath 脚本文件路径
     */
    void unloadScript(Path scriptPath);

    /**
     * 脚本是否已加载
     *
     * @param scriptPath 脚本文件路径
     * @return 是否已加载
     */
    default boolean isLoaded(Path scriptPath) {
        return false;
    }
}
