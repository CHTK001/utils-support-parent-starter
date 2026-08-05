package com.chua.runtime.plugin;

/**
 * 运行时插件 SPI 接口。
 *
 * <p>所有插件实现类必须实现此接口，并通过 {@code META-INF/services/com.chua.runtime.plugin.Plugin} 注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Plugin {

    /**
     * 插件名称。
     *
     * @return 插件名称
     */
    String name();

    /**
     * 插件版本。
     *
     * @return 版本字符串
     */
    String version();

    /**
     * 初始化插件。
     *
     * @param context 插件上下文
     * @throws Exception 初始化异常
     */
    void init(PluginContext context) throws Exception;

    /**
     * 插件开始工作。
     *
     * @throws Exception 启动异常
     */
    void start() throws Exception;

    /**
     * 停止插件。
     *
     * @throws Exception 停止异常
     */
    void stop() throws Exception;

    /**
     * 插件状态。
     *
     * @return 状态描述
     */
    String status();

    /**
     * 插件是否已启动。
     *
     * @return 已启动返回 true
     */
    boolean isRunning();
}