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
 * 提供 publish/subscribe API，启动时若无外部分派器则回退至本地 Chronicle 实现。
 * Server 模式下所有 sinkId 共享一个 topic，客户端模式下按 agentId + sinkId 隔离。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ReactorDataSyncExecutor {

    protected DispatcherProvider chronicleProvider;
    private final String agentId;
    private final boolean serverMode;

    /**
     * 直连派发模式：同 JVM 内 publish 直接调用 subscriber，绕过 Chronicle 派发层。
     */
    private volatile boolean directDispatch;
    private volatile Consumer<List<Map<String, Object>>> directConsumer;

    public ReactorDataSyncExecutor(String agentId, boolean serverMode) {
        this.agentId = agentId;
        this.serverMode = serverMode;
    }

    public String getAgentId() {
        return agentId;
    }

    public boolean isServerMode() {
        return serverMode;
    }

    /**
     * 启用直连派发模式（同 JVM 内 publish 直接调用 subscriber，绕过 Chronicle）。
     * 必须在 {@link #start()} 之前调用。
     */
    public void setDirectDispatch(boolean directDispatch) {
        this.directDispatch = directDispatch;
    }

    public void setDispatcherProvider(DispatcherProvider chronicleProvider) {
        this.chronicleProvider = chronicleProvider;
    }

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
     * 订阅 sinkId 对应 topic。
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
     * 向 sinkId 对应 topic 发布数据。
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

    private String buildTopic(String sinkId) {
        if (serverMode) {
            return "server-" + agentId;
        }
        return "consumer-" + agentId + "-" + sinkId;
    }
}
