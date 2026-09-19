package com.chua.common.support.network.ipc;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.IpcServerFilter;
import com.chua.common.support.network.server.handler.ServerHandlerFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IPC 服务器，处理浏览器端到 Java 端的进程间通信请求。
 *
 * <p>继承 {@link AbstractServer}，内部持有 {@link IpcServerFilter}，
 * 由 filter 的 {@link ServerHandlerFactory} 统一管理 IPC 方法路由。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
@Slf4j
public class IpcServer extends AbstractServer {
    /**
     * 构造 IPC 服务器。
     *
     * @param setting 服务器配置
     */
    public IpcServer(ServerSetting setting) {
        super(setting);
        addFilter(new IpcServerFilter(getObjectContext()));
    }

    @Override
    /**
     * 获取ProtocolType
    */
    public ProtocolType getProtocolType() {
        return ProtocolType.IPC;
    }

    @Override
    /**
     * Do开始
    */
    protected void doStart() {
        // 路由由 IpcServerFilter 在构造时自动发现
    }

    @Override
    /**
     * Do停止
    */
    protected void doStop() {
    }

    /**
     * 处理 IPC 消息。
     *
     * @param source 消息来源标识
     * @param path   方法路径
     * @param body   请求体
     * @return JSON 响应字符串
     */
    public String handleMessage(String source, String path, String body) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", source);
        response.put("path", path);
        response.put("body", body);
        response.put("success", true);
        response.put("timestamp", System.currentTimeMillis());
        return Json.toJson(response);
    }

}
