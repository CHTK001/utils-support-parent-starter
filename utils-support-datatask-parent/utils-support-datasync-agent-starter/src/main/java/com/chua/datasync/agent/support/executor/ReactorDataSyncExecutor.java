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

    /** Chronicle 调度提供者 */
    protected DispatcherProvider chronicleProvider;
    /** 代理标识 */
    private final String agentId;
    /** 服务端模式 */
    private final boolean serverMode;

    /**
     * 直连派发模式：同 JVM 内 发布 直接调用 subscriber，绕过 Chronicle 派发层。
     */
    private volatile boolean directDispatch;
    /** 直接消费器 */
    private volatile Consumer<List<Map<String, Object>>> directConsumer;

    /**
     * 创建 reactor数据同步执行器 实例
     * @param agentId Agent标识
     * @param serverMode 布尔值
     * @param serverMode 服务端mode
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

    /** 开始 */
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

    /** 停止 */
    public void stop() {
        if (directDispatch) {
            directConsumer = null;
            return;
        }
        if (chronicleProvider != null) {
            chronicleProvider.close();
        }
    }

    /**
     * 订阅 sinkid 对应 topic。
     *
     * @param sinkId   sink 标识
     * @param consumer 消息回调
     */
    public void subscribe(String sinkId, Consumer<List<Map<String, Object>>> consumer) {
        if (directDispatch) {
            directConsumer = consumer;
            return;
        }
        chronicleProvider.subscribe(new ConsumerDispatcherDefinition<>(consumer, List.class, List.of(buildTopic(sinkId))));
    }

    /**
     * 向 sinkid 对应 topic 发布数据。
     *
     * @param sinkId sink 标识
     * @param data   待发布数据列表
     */
    public void publish(String sinkId, List<Map<String, Object>> data) {
        if (directDispatch) {
            var c = directConsumer;
            if (c != null) {
                try {
                    c.accept(data);
                } catch (Exception e) {
                    log.error("直连派发异常", e);
                }
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
            return "server-" + agentId;
        }
        return "consumer-" + agentId + "-" + sinkId;
    }
}
