package com.chua.common.support.network.protocol.server;

import com.chua.common.support.network.server.ServerSetting;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullUnmarked;

/**
 * 协议服务器抽象基类。
 * <p>提供生命周期骨架：子类只需实现 {@link #doStart()} 和 {@link #doStop()}。</p>
 *
 * @author CH
 * @since 2026/07/26
 */
@NullUnmarked
@Slf4j
public abstract class AbstractProtocolServer {

    /**
     * 服务器配置设置。
     */
    protected final ServerSetting serverSetting;

    /**
     * 服务器是否正在运行。
     */
    protected volatile boolean running;

    /**
     * 子类必须提供带 {@link ServerSetting} 的构造函数。
     *
     * @param serverSetting 服务器配置
     */
    public AbstractProtocolServer(ServerSetting serverSetting) {
        this.serverSetting = serverSetting;
    }

    /**
     * 启动服务器。
     */
    public void start() throws Exception {
        if (running) {
            log.warn("Server already running");
            return;
        }
        doStart();
        running = true;
        log.info("[ProtocolServer] {} started on {}:{}", getClass().getSimpleName(),
                serverSetting.getHost(), serverSetting.getPort());
    }

    /**
     * 停止服务器。
     */
    public void stop() throws Exception {
        if (!running) {
            return;
        }
        doStop();
        running = false;
        log.info("[ProtocolServer] {} stopped", getClass().getSimpleName());
    }

    /**
     * 子类实现：启动服务器逻辑。
     */
    protected abstract void doStart() throws Exception;

    /**
     * 子类实现：停止服务器逻辑。
     */
    protected abstract void doStop() throws Exception;

    /**
     * 检查服务器是否正在运行。
     *
     * @return true 如果正在运行
     */
    public boolean isRunning() {
        return running;
    }
}
