package com.chua.chronicle.support.collector;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.CollectData;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.model.PipelineState;
import com.chua.datalake.support.spi.collection.ActiveCollector;
import com.chua.datalake.support.spi.collection.DataHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Chronicle Queue 主动采集器。
 *
 * <p>监听 Chronicle Queue 中的消息，将数据推入 Pipeline 处理。
 * Chronicle Queue 是低延迟、持久化的消息队列，适合金融交易等高性能场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("chronicle")
public class ChronicleActiveCollector implements ActiveCollector {

    /**
     * 运行状态
     */
    private volatile boolean running;

    /**
     * 数据处理器
     */
    private DataHandler handler;

    /**
     * Topic 与 Pipeline ID 映射
     */
    protected final Map<String, String> topicToPipeline = new ConcurrentHashMap<>();

    /**
     * 已订阅的 Topic
     */
    protected final Set<String> subscribedTopics = new CopyOnWriteArraySet<>();

    /**
     * 采集线程
     */
    private Thread collectorThread;

    @Override
    public String protocol() {
        return "CHRONICLE";
    }

    @Override
    public int defaultPort() {
        return 0;
    }

    @Override
    public void start(int port) throws Exception {
        if (running) {
            return;
        }
        running = true;
        collectorThread = new Thread(this::pollLoop, "chronicle-collector");
        collectorThread.setDaemon(true);
        collectorThread.start();
        log.info("[ChronicleActiveCollector] started");
    }

    /**
     * 轮询 Chronicle Queue 并处理消息。
     */
    private void pollLoop() {
        while (running) {
            try {
                /*
                 * 实际实现中，此处应读取 Chronicle Queue 的新消息。
                 * 此处模拟轮询逻辑。
                 */
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[ChronicleActiveCollector] poll error", e);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (collectorThread != null) {
            collectorThread.interrupt();
            collectorThread = null;
        }
        log.info("[ChronicleActiveCollector] stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void registerMapping(String topic, String pipelineId) {
        if (topic != null && pipelineId != null) {
            topicToPipeline.put(topic, pipelineId);
            subscribedTopics.add(topic);
            log.info("[ChronicleActiveCollector] registered mapping: topic={} -> pipelineId={}",
                    topic, pipelineId);
        }
    }

    @Override
    public void unregisterMapping(String topic) {
        if (topic != null) {
            topicToPipeline.remove(topic);
            subscribedTopics.remove(topic);
            log.info("[ChronicleActiveCollector] unregistered mapping: topic={}", topic);
        }
    }

    @Override
    public Map<String, String> getMappings() {
        return Map.copyOf(topicToPipeline);
    }

    @Override
    public Set<String> subscribedTopics() {
        return Set.copyOf(subscribedTopics);
    }

    @Override
    public void setHandler(DataHandler handler) {
        this.handler = handler;
    }

    @Override
    public Map<String, Object> getStatus() {
        return Map.of(
                "protocol", protocol(),
                "running", isRunning(),
                "mappings", topicToPipeline.size(),
                "topics", subscribedTopics()
        );
    }

    /**
     * 模拟接收到 Chronicle Queue 消息。
     * <p>用于测试或手动触发数据采集。</p>
     *
     * @param topic   主题
     * @param payload 消息内容
     */
    public void simulateMessage(String topic, String payload) {
        String pipelineId = topicToPipeline.get(topic);
        if (pipelineId == null) {
            log.warn("[ChronicleActiveCollector] no pipeline mapping for topic: {}", topic);
            return;
        }
        if (handler == null) {
            log.warn("[ChronicleActiveCollector] no handler registered");
            return;
        }

        CollectData data = new CollectData();
        data.setTopic(topic);
        data.setPayload(payload);
        data.setProtocol("CHRONICLE");

        DataEnvelope envelope = new DataEnvelope();
        envelope.setPipelineId(pipelineId);
        envelope.setTopics(Set.of(topic));
        envelope.setTimestamp(System.currentTimeMillis());
        envelope.setState(PipelineState.RECEIVED);

        handler.handle(pipelineId, envelope);
        log.info("[ChronicleActiveCollector] message processed: topic={}, pipelineId={}",
                topic, pipelineId);
    }
}
