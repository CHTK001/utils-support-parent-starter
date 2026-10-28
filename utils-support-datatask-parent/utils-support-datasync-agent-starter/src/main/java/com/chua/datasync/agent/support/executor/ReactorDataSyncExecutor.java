package com.chua.datasync.agent.support.executor;

import com.chua.chronicle.support.dispatcher.ChronicleDispatcherProvider;
import com.chua.datasync.agent.support.DataSyncAgentException;
import com.chua.common.support.concurrent.dispatcher.ConsumerDispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 基于响应式分派器（Chronicle）的数据同步执行器封装。
 * <p>
 * 提供 发布/订阅 API，启动时若无外部分派器则回退至本地 Chronicle 实现。
 * 服务端 模式下所有 sinkid 共享一个 topic，客户端模式下按 Agentid + sinkid 隔离。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ReactorDataSyncExecutor {

    /**
     * Chronicle 调度提供者
    */
    protected DispatcherProvider chronicleProvider;
    /**
     * 代理标识
    */
    private final String agentId;
    /**
     * 服务端模式
    */
    private final boolean serverMode;

    /**
     * 直连派发模式：同 JVM 内 发布 直接调用 subscriber，绕过 Chronicle 派发层。
     */
    private volatile boolean directDispatch;
    /**
     * 直连消费者表：sinkId → 消费者。
     *
     * <p><b>为什么是 Map 而不是单个消费者</b>：一个执行器按 outputId 池化，
     * 同一个 outputId 下可以挂多个 sink（一份源写多张表）。原实现只存一个
     * {@code directConsumer}，注册第二个 sink 会覆盖第一个，结果只有最后注册的
     * 目标能收到数据，其余目标静默零写入——这类错误不报错，最难发现。</p>
     *
     * <p>键取 sinkId：直连模式没有 topic 概念，发布方 {@code publish(sinkId, data)}
     * 的第一个参数就是 sinkId，按它路由语义最直接。</p>
     */
    private final java.util.Map<String, Consumer<List<Map<String, Object>>>> directConsumers =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 创建 reactor数据同步执行器 实例
     * @param agentId Agent标识
     * @param serverMode 布尔值
     */
    public ReactorDataSyncExecutor(String agentId, boolean serverMode) {
        this.agentId = agentId;
        this.serverMode = serverMode;
    }

    /**
     * 获取Agentid
     *
     * @return 获取Agentid的结果
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 是否服务端mode
     *
     * @return 是否服务端mode的结果
     */
    public boolean isServerMode() {
        return serverMode;
    }

    /**
     * 启用直连派发模式（同 JVM 内 发布 直接调用 subscriber，绕过 Chronicle）。
     * 必须在 {@link #start()} 之前调用。
     * @param directDispatch directdispatch
     */
    public void setDirectDispatch(boolean directDispatch) {
        this.directDispatch = directDispatch;
    }

    /**
     * 设置dispatcher提供者
     *
     * @param chronicleProvider chronicle提供者
     */
    public void setDispatcherProvider(DispatcherProvider chronicleProvider) {
        this.chronicleProvider = chronicleProvider;
    }

    /**
     * 开始
    */
    public void start() {
        if (directDispatch) {
            log.info("直连派发模式已启用 (agentId={})", agentId);
            return;
        }
        if (chronicleProvider == null) {
            String path = System.getProperty("java.io.tmpdir") + "/chronicle-datasync";
            chronicleProvider = new ChronicleDispatcherProvider(
                    DispatcherConfig.builder().dataPath(path).build());
        }
        chronicleProvider.start();
    }

    /**
     * 停止
    */
    public void stop() {
        if (directDispatch) {
            directConsumers.clear();
            return;
        }
        if (chronicleProvider != null) {
            chronicleProvider.close();
        }
    }

    /**
     * 订阅 sinkid 对应 topic。
     *
     * <p>同一个 sinkId 重复订阅时**替换**旧消费者（而不是叠加），
     * 保证改配置后新实例生效、旧实例不再被调用。</p>
     *
     * @param sinkId   sink 标识
     * @param consumer 消息回调
     */
    public void subscribe(String sinkId, Consumer<List<Map<String, Object>>> consumer) {
        if (sinkId == null || sinkId.isBlank() || consumer == null) {
            return;
        }
        if (directDispatch) {
            directConsumers.put(sinkId, consumer);
            return;
        }
        chronicleProvider.subscribe(new ConsumerDispatcherDefinition<>(consumer, List.class,
                List.of(buildTopic(sinkId))));
    }

    /**
     * 取消某个 sinkid 的订阅。
     *
     * <p>直连模式必须支持取消：目标表 / 写入模式改动后要换 sink 实例，
     * 若旧订阅留在表里，发布仍会打到已关闭的 Sink 上——表现为「改了配置不生效」。</p>
     *
     * @param sinkId sink 标识
     */
    public void unsubscribe(String sinkId) {
        if (sinkId == null || sinkId.isBlank()) {
            return;
        }
        if (directDispatch) {
            directConsumers.remove(sinkId);
        }
    }

    /**
     * 向 sinkid 对应 topic 发布数据。
     *
     * @param sinkId sink 标识
     * @param data   待发布数据列表
     */
    public void publish(String sinkId, List<Map<String, Object>> data) {
        if (directDispatch) {
            Consumer<List<Map<String, Object>>> c = sinkId == null ? null : directConsumers.get(sinkId);
            if (c == null) {
                log.warn("直连派发未找到订阅者，数据丢弃: agentId={}, sinkId={}", agentId, sinkId);
                return;
            }
            try {
                c.accept(data);
            } catch (Exception e) {
                log.error("直连派发异常: agentId={}, sinkId={}", agentId, sinkId, e);
            }
            return;
        }
        chronicleProvider.publish(buildTopic(sinkId), data);
    }

    /**
     * 构建Topic
     *
     * @param sinkId sinkid
     * @return 构建topic的结果
     */
    private String buildTopic(String sinkId) {
        if (serverMode) {
            // topic 必须带 sinkId：一源多目标时多个 sink 挂在同一个 agent 下，
            // 若共用 "server-<agentId>" 一个 topic，所有 sink 会互相抢消息，
            // 表现为「A 表收到 B 表的数据」或「只有最后一个目标有数据」。
            return "server-" + agentId + "-" + sinkId;
        }
        return "consumer-" + agentId + "-" + sinkId;
    }
}
