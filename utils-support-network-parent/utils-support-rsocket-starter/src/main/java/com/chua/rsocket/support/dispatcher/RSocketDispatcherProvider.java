package com.chua.rsocket.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.lang.json.Json;
import io.rsocket.core.RSocketConnector;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
   * r套接字 分发器提供者，基于 r套接字 Java 客户端连接远程 r套接字 服务。
 * <p>
   * 作为客户端接入远程 r套接字 服务，通过 请求流 订阅 topic，
 * 服务端推送消息后本地逐条分发给对应订阅定义。
   * 本地不启动任何服务器，所有连接都指向外部已部署的 r套接字 服务。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("rsocket")
public class RSocketDispatcherProvider extends AbstractDispatcherProvider {

    /**
      * r套接字 客户端连接
     */
    private io.rsocket.RSocket rSocket;

    /**
     * topic 到订阅定义的映射
     */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /**
     * topic 到远程订阅流的映射
     */
    private final Map<String, Disposable> topicSubscriptions = new ConcurrentHashMap<>();

    /**
      * 构造 r套接字 分发器提供者。
     *
     * @param config 分发器配置
     */
    public RSocketDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    /** 开始 */
    public void start() {
        if (rSocket != null && !rSocket.isDisposed()) {
            return;
        }
        String host = parseHost();
        int port = parsePort();
        long timeout = config.getConnectionTimeoutMillis() > 0
                ? config.getConnectionTimeoutMillis() : 5000;

        rSocket = RSocketConnector.create()
                .keepAlive(Duration.ofSeconds(30), Duration.ofSeconds(90))
                .connect(TcpClientTransport.create(host, port))
                .block(Duration.ofMillis(timeout));

        if (rSocket == null) {
            throw new RuntimeException("RSocket 连接远程服务失败: " + host + ":" + port);
        }
        log.info("[RSocket] 已连接远程服务: {}:{}", host, port);
        for (String topic : definitionMap.keySet()) {
            registerRemoteSubscription(topic);
        }
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object body) {
        if (rSocket == null || rSocket.isDisposed()) {
            log.warn("[RSocket] 未连接远程服务，无法发布消息: topic={}", topic);
            return;
        }
        String message = body == null ? "" : body.toString();
        String payload = Json.toJson(java.util.Map.of(
                "action", "publish",
                "topic", topic,
                "data", message
        ));
        rSocket.fireAndForget(DefaultPayload.create(payload)).block();
        log.debug("[RSocket] 已发布消息: topic={}, body={}", topic, message);
    }

    @Override
    /** 订阅 */
    public void subscribe(DispatcherDefinition definition) {
        for (String topic : definition.getTopics()) {
            definitionMap.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(definition);
            if (rSocket != null && !rSocket.isDisposed()) {
                registerRemoteSubscription(topic);
            }
            log.debug("[RSocket] 已订阅主题: {}", topic);
        }
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(DispatcherDefinition definition) {
        for (String topic : definition.getTopics()) {
            List<DispatcherDefinition> defs = definitionMap.get(topic);
            if (defs != null) {
                defs.remove(definition);
                if (defs.isEmpty()) {
                    definitionMap.remove(topic);
                }
            }
            Disposable disposable = topicSubscriptions.remove(topic);
            if (disposable != null) {
                disposable.dispose();
            }
            log.debug("[RSocket] 已取消订阅主题: {}", topic);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        for (Disposable disposable : topicSubscriptions.values()) {
            try {
                disposable.dispose();
            } catch (Exception ignored) {
                // 取消订阅异常忽略
            }
        }
        topicSubscriptions.clear();
        if (rSocket != null && !rSocket.isDisposed()) {
            rSocket.dispose();
            rSocket = null;
            log.info("[RSocket] 分发器客户端已关闭");
        }
        definitionMap.clear();
    }

    /**
     * 在远程服务上注册 topic 订阅流。
     * <p>
      * 通过 请求流 向远端发送订阅请求，远端推送消息后逐条分发。
     * 同一个 topic 只注册一次。
     * </p>
     *
     * @param topic 主题名称
     */
    private void registerRemoteSubscription(String topic) {
        if (topicSubscriptions.containsKey(topic)) {
            return;
        }
        String subscribePayload = Json.toJson(java.util.Map.of(
                "action", "subscribe",
                "topic", topic
        ));
        Disposable disposable = rSocket.requestStream(DefaultPayload.create(subscribePayload))
                .doOnNext(payload -> {
                    String data = payload.getDataUtf8();
                    dispatchToLocal(topic, data);
                })
                .doOnError(e -> log.error("[RSocket] 订阅消息接收失败: topic={}", topic, e))
                .subscribe();
        topicSubscriptions.put(topic, disposable);
        log.debug("[RSocket] 已在远程服务注册订阅: {}", topic);
    }

    /**
     * 将远端推送的消息分发给本地订阅定义。
     *
     * @param topic 主题名称
     * @param data  消息内容
     */
    private void dispatchToLocal(String topic, String data) {
        List<DispatcherDefinition> defs = definitionMap.get(topic);
        if (defs == null) {
            return;
        }
        for (DispatcherDefinition def : defs) {
            try {
                def.dispatch(data);
            } catch (Exception e) {
                log.error("[RSocket] 分发事件失败: topic={}", topic, e);
            }
        }
    }

    /**
     * 从配置 URL 解析端口号。
     * <p>
     * 支持 rsocket://、http://、https:// 前缀。
     * 未配置或解析失败时返回默认端口 7000。
     * </p>
     *
     * @return 端口号
     */
    private int parsePort() {
        String url = config.getUrl();
        if (url == null || url.isEmpty()) {
            return 7000;
        }
        url = url.replaceAll("^rsocket://", "").replaceAll("^http://", "").replaceAll("^https://", "");
        String[] parts = url.split(":");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1].replaceAll("/.*", ""));
            } catch (NumberFormatException ignored) {
                // 端口解析失败时使用默认端口
            }
        }
        if (parts.length == 1 && !parts[0].isEmpty()) {
            try {
                return Integer.parseInt(parts[0]);
            } catch (NumberFormatException ignored) {
                // 端口解析失败时使用默认端口
            }
        }
        return 7000;
    }

    /**
     * 从配置 URL 解析主机地址。
     * <p>
     * 支持 rsocket://、http://、https:// 前缀。
     * 未配置或解析失败时返回默认主机 localhost。
     * </p>
     *
     * @return 主机地址
     */
    private String parseHost() {
        String url = config.getUrl();
        if (url == null || url.isEmpty()) {
            return "localhost";
        }
        url = url.replaceAll("^rsocket://", "").replaceAll("^http://", "").replaceAll("^https://", "");
        String[] parts = url.split(":");
        if (parts.length >= 1 && !parts[0].isEmpty()) {
            return parts[0];
        }
        return "localhost";
    }
}
