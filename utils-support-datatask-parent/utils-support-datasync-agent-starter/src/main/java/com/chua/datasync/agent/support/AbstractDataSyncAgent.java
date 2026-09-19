package com.chua.datasync.agent.support;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 数据同步 Agent 抽象基类，统一管理 源、Sink 与生命周期。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractDataSyncAgent implements DataSyncAgent {

    /**
     * Agent 唯一标识
     */
    private final String agentId;
    /**
     * 源 实例列表
     */
    private final List<DataSyncAgentSource> sources;
    /**
     * Sink 实例列表
     */
    private final List<DataSyncAgentSink> sinks;

    /**
     * 构造 Agent。
     *
     * @param agentId Agent 唯一标识
     */
    protected AbstractDataSyncAgent(String agentId) {
        this.agentId = agentId;
        this.sources = new ArrayList<>();
        this.sinks = new ArrayList<>();
    }

    /**
     * 添加 源 实例。
     *
     * @param source 源 实例
     */
    protected void addSource(DataSyncAgentSource source) {
        this.sources.add(source);
    }

    /**
     * 添加 Sink 实例。
     *
     * @param sink Sink 实例
     */
    protected void addSink(DataSyncAgentSink sink) {
        this.sinks.add(sink);
    }

    @Override
    /** Agentid */
    public String agentId() {
        return agentId;
    }

    /**
     * 获取所有 源 实例。
     *
     * @return Source 列表
     */
    public List<DataSyncAgentSource> sources() {
        return Collections.unmodifiableList(sources);
    }

    @Override
    /** 获取源 */
    public DataSyncAgentSource getSource(String sourceId) {
        for (DataSyncAgentSource source : sources) {
            if (source.sourceId().equals(sourceId)) {
                return source;
            }
        }
        return null;
    }

    /**
     * 获取所有 Sink 实例。
     *
     * @return Sink 列表
     */
    public List<DataSyncAgentSink> sinks() {
        return Collections.unmodifiableList(sinks);
    }

    @Override
    /** 获取Sink */
    public DataSyncAgentSink getSink(String sinkId) {
        for (DataSyncAgentSink sink : sinks) {
            if (sink.sinkId().equals(sinkId)) {
                return sink;
            }
        }
        return null;
    }

    @Override
    /** 开始 */
    public void start() {
        for (DataSyncAgentSource source : sources) {
            try {
 // 启动 源 资源
            } catch (Exception e) {
                throw new DataSyncAgentException("启动 Source 失败: " + source.sourceId(), e);
            }
        }
        for (DataSyncAgentSink sink : sinks) {
            try {
                // 启动 Sink 资源
            } catch (Exception e) {
                throw new DataSyncAgentException("启动 Sink 失败: " + sink.sinkId(), e);
            }
        }
    }

    @Override
    /** 停止 */
    public void stop() {
        for (DataSyncAgentSource source : sources) {
            try {
                source.close();
            } catch (Exception e) {
                log.warn("关闭 Source 异常: sourceId={}", source.sourceId(), e);
            }
        }
        for (DataSyncAgentSink sink : sinks) {
            try {
                sink.close();
            } catch (Exception e) {
                log.warn("关闭 Sink 异常: sinkId={}", sink.sinkId(), e);
            }
        }
    }
}
