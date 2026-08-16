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

    /**
     * 分派器提供器（外部注入或默认 Chronicle）
     */
    protected DispatcherProvider chronicleProvider;

    /**
     * Agent 唯一标识
     */
    private final String agentId;

    /**
     * 是否为服务端模式（true 则共享一个 topic，否则按 sinkId 隔离）
     */
    private final boolean serverMode;

    /**
     * @param agentId    Agent 标识
     * @param serverMode 是否服务端模式
     */
    public ReactorDataSyncExecutor(String agentId, boolean serverMode) {
        this.agentId = agentId;
        this.serverMode = serverMode;
    }

    /**
     * @return Agent 标识
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * @return 是否服务端模式
     */
    public boolean isServerMode() {
        return serverMode;
    }

    /**
     * 注入自定义分派器。
     *
     * @param chronicleProvider DispatcherProvider 实例
     */
    public void setDispatcherProvider(DispatcherProvider chronicleProvider) {
        this.chronicleProvider = chronicleProvider;
    }

    /**
     * 启动执行器；未注入分派器时回退到基于 java.io.tmpdir/chronicle-datasync 的本地 Chronicle。
     */
    public void start() {
        if (chronicleProvider == null) {
            String path = System.getProperty("java.io.tmpdir") + "/chronicle-datasync";
            chronicleProvider = new ChronicleDispatcherProvider(
                    DispatcherConfig.builder().dataPath(path).build());
        }
        chronicleProvider.start();
    }

    /**
     * 停止执行器，关闭分派器。
     */
    public void stop() {
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
        chronicleProvider.subscribe(new ConsumerDispatcherDefinition<>(consumer, List.of(buildTopic(sinkId))));
    }

    /**
     * 向 sinkId 对应 topic 发布数据。
     *
     * @param sinkId sink 标识
     * @param data   待发布数据列表
     */
    public void publish(String sinkId, List<Map<String, Object>> data) {
        chronicleProvider.publish(buildTopic(sinkId), data);
    }

    /**
     * 构造 topic 名。服务端模式下为 {@code server-{agentId}}，客户端模式下按 sinkId 隔离。
     *
     * @param sinkId sink 标识
     * @return topic 名称
     */
    private String buildTopic(String sinkId) {
        if (serverMode) {
            return "server-" + agentId;
        }
        return "consumer-" + agentId + "-" + sinkId;
    }
}
