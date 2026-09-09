package com.chua.common.support.network.protocol.sync.http;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.http.EventSourceListener;
import com.chua.common.support.network.http.HttpClient;
import com.chua.common.support.network.http.SseClient;
import com.chua.common.support.network.http.SseClientInvoker;
import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.sync.AbstractSyncClient;
import com.chua.common.support.network.protocol.sync.SyncMessage;
import com.chua.common.support.text.json.Json;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP Streaming同步客户端
 * 作者: CH
 * 创建时间: 2026-02-08
 * 版本: 1.0
 */
@Slf4j
@Spi("http-stream-sync")
public class HttpStreamingSyncClient extends AbstractSyncClient {

    private SseClientInvoker invoker;
    private String streamPath = "/stream";
    private String syncPath = "/sync";

    /**
     * 构造方法
     * 作者: CH
     * 创建时间: 2026-02-08
     * 版本: 1.0
     * @param clientSetting 客户端配置
     */
    public HttpStreamingSyncClient(ClientSetting clientSetting) {
        super(clientSetting);
        Object pathOpt = clientSetting.getOption("streamPath");
        if (pathOpt instanceof String s && !s.isEmpty()) {
            streamPath = s.startsWith("/") ? s : ("/" + s);
        }
        Object syncPathOpt = clientSetting.getOption("syncPath");
        if (syncPathOpt instanceof String s && !s.isEmpty()) {
            syncPath = s.startsWith("/") ? s : ("/" + s);
        }
    }

    /**
     * 连接服务端
     * @return 是否连接成功
     * @throws Exception 异常
     */
    @Override
    protected boolean doConnect() throws Exception {
        String url = String.format("http://%s:%d%s",
                clientSetting.getHost(),
                clientSetting.getPort(),
                streamPath);
        long connectTimeout = clientSetting.getConnectTimeoutMillis() > 0
                ? clientSetting.getConnectTimeoutMillis()
                : 10000;
        long readTimeout = clientSetting.getReadTimeoutMillis() > 0
                ? clientSetting.getReadTimeoutMillis()
                : 60000;

        invoker = SseClient.get()
                .url(url)
                .connectTimeout(connectTimeout)
                .readTimout(readTimeout)
                .newInvoker("jdk");

        try {
            invoker.execute(new InternalEventSourceListener());
            return true;
        } catch (Exception e) {
            log.error("HTTP Streaming客户端连接失败", e);
            return false;
        }
    }

    /**
     * 断开连接
     * @throws Exception 异常
     */
    @Override
    protected void doDisconnect() throws Exception {
        if (invoker != null) {
            try {
                invoker.close();
            } catch (Exception ignored) {
            }
        }
        notifyConnectionState(false);
    }

    /**
     * 订阅主题
     * @param topic 主题
     */
    @Override
    protected void doSubscribe(String topic) {
    }

    /**
     * 取消订阅
     * @param topic 主题
     */
    @Override
    protected void doUnsubscribe(String topic) {
    }

    /**
     * 发布消息
     * @param topic 主题
     * @param data 数据
     * @throws Exception 异常
     */
    @Override
    protected void doPublish(String topic, Object data) throws Exception {
        String url = String.format("http://%s:%d%s",
                clientSetting.getHost(),
                clientSetting.getPort(),
                syncPath);
        SyncMessage msg = SyncMessage.of(topic, data, clientId);
        String json = Json.toJson(msg);
        HttpClient.post(url)
                .json()
                .connectTimeout(clientSetting.getConnectTimeoutMillis())
                .readTimeout(clientSetting.getReadTimeoutMillis())
                .body(json)
                .execute();
    }

    private class InternalEventSourceListener implements EventSourceListener {
        /**
         * 连接打开
         */
        @Override
        public void onOpen() {
            notifyConnectionState(true);
        }

        /**
         * 接收事件
         * @param id 事件ID
         * @param type 事件类型
         * @param data 事件数据
         */
        @Override
        public void onEvent(String id, String type, String data) {
            try {
                SyncMessage msg = Json.fromJson(data, SyncMessage.class);
                if (msg != null) {
                    notifyMessage(msg.getTopic(), msg.getData());
                    return;
                }
            } catch (Exception ignored) {
            }
            notifyMessage(type != null ? type : "message", data);
        }

        /**
         * 失败回调
         * @param t 异常
         */
        @Override
        public void onFailure(Throwable t) {
            log.error("HTTP Streaming客户端失败", t);
            notifyConnectionState(false);
        }

        /**
         * 连接关闭
         */
        @Override
        public void onClosed() {
            notifyConnectionState(false);
        }
    }
}
