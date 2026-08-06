package com.chua.runtime.apm;

import com.chua.runtime.apm.handler.FileHandler;
import com.chua.runtime.apm.handler.LogHandler;
import com.chua.runtime.apm.handler.NetHandler;
import com.chua.runtime.apm.handler.TraceHandler;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * APM 启动器 — 程序化启动所有 APM 处理器。
 *
 * <p>用途：</p>
 * <ul>
 *   <li>由 runtime-starter 调用，为指定应用注入插桩能力</li>
 *   <li>由 SpyBootstrap 的插件机制间接使用（SPI 注册）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ApmBootstrap {

    /**
     * 处理器列表
     */
    private final List<Plugin> handlers;

    /**
     * 是否已启动
     */
    private boolean started;

    /**
     * 创建 APM 启动器。
     *
     * @param pluginDir 插件目录
     */
    public ApmBootstrap(Path pluginDir) {
        this.handlers = new ArrayList<>();
        this.started = false;
        registerDefaults(pluginDir);
    }

    /**
     * 注册默认的四个处理器。
     *
     * @param pluginDir 插件目录
     */
    private void registerDefaults(Path pluginDir) {
        PluginContext context = new PluginContext(pluginDir);
        handlers.add(new LogHandler());
        handlers.add(new NetHandler());
        handlers.add(new FileHandler());
        handlers.add(new TraceHandler());
        try {
            for (Plugin handler : handlers) {
                handler.init(context);
            }
        } catch (Exception e) {
            log.error("APM 处理器初始化失败", e);
        }
    }

    /**
     * 注册自定义处理器。
     *
     * @param handler 插件处理器
     */
    public void addHandler(Plugin handler) {
        handlers.add(handler);
    }

    /**
     * 启动所有处理器。
     */
    public void start() {
        if (started) {
            return;
        }
        for (Plugin handler : handlers) {
            try {
                handler.start();
                log.info("APM 处理器[{}] 启动", handler.name());
            } catch (Exception e) {
                log.error("APM 处理器[{}] 启动失败", handler.name(), e);
            }
        }
        started = true;
    }

    /**
     * 停止所有处理器。
     */
    public void stop() {
        for (int i = handlers.size() - 1; i >= 0; i--) {
            Plugin handler = handlers.get(i);
            try {
                handler.stop();
                log.info("APM 处理器[{}] 停止", handler.name());
            } catch (Exception e) {
                log.error("APM 处理器[{}] 停止失败", handler.name(), e);
            }
        }
        started = false;
    }

    /**
     * 所有处理器状态。
     *
     * @return 状态字符串
     */
    public String status() {
        StringBuilder sb = new StringBuilder();
        for (Plugin handler : handlers) {
            sb.append(handler.status()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 获取指定类型的处理器。
     *
     * @param type 处理器类型
     * @param <T>  处理器泛型
     * @return 处理器实例，不存在返回 null
     */
    public <T extends Plugin> T getHandler(Class<T> type) {
        for (Plugin handler : handlers) {
            if (type.isInstance(handler)) {
                return type.cast(handler);
            }
        }
        return null;
    }

    /**
     * 获取所有已注册的处理器。
     *
     * @return 处理器列表
     */
    public List<Plugin> getHandlers() {
        return java.util.Collections.unmodifiableList(handlers);
    }

    /**
     * 是否已启动。
     *
     * @return 已启动返回 true
     */
    public boolean isStarted() {
        return started;
    }
}
