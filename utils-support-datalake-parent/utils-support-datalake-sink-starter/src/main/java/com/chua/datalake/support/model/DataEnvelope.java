package com.chua.datalake.support.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据信封，封装流转中的业务数据与元数据。
 *
 * <p>每条数据进入管道后都会被包装为 DataEnvelope 在管线中流转。
 * 管线各阶段（Filter、Parser、Cleaner、Standardizer、Sink）通过读取和修改
 * {@link #getParsed()} 完成数据处理/清洗。管线终点通过 DispatcherProvider
 * 发布至订阅的 sink，sink 解开 envelope 执行业务逻辑。</p>
 *
 * <p>关键字段说明：</p>
 * <ul>
 *   <li>{@code parsed} 存储当前阶段的业务数据 Map</li>
 *   <li>{@code pipelineId} 绑定所属管线 ID，用于追踪/路由</li>
 *   <li>{@code timestamp} 用作全序 offset，{@code SubscriberManager} 严格有序消费</li>
 *   <li>{@code state} 对应当前阶段 PipelineState 状态类型</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class DataEnvelope {

    /**
     * 当前阶段的业务数据，核心存储结构为 {@code Map<String,Object>}
     */
    private Map<String, Object> parsed;

    /**
     * 所属管线 ID，同一管线同结构在这条流上处理/分析
     */
    private String pipelineId;

    /**
     * 当期所处的管线阶段状态
     */
    private PipelineState state;

    /**
     * 受理时间戳（毫秒），用作数据排序的 monotonically increasing offset
     */
    private long timestamp;

    /**
     * 追踪 ID，可观察性标识
     */
    private String traceId;

    /**
     * 数据所属主题列表，用于路由与分发过滤
     */
    private Set<String> topics;

    /**
     * 数据迹日志记录（附加信息）
     */
    private final List<String> trace = new ArrayList<>();

    /**
     * 创建空 DataEnvelope
     */
    public DataEnvelope() {
    }

    /**
     * 以指定业务数据创建 Envelope
     *
     * @param parsed 当前业务数据（非空）
     */
    public DataEnvelope(Map<String, Object> parsed) {
        this.parsed = parsed;
    }

    /**
     * 返回当前业务数据
     *
     * @return 已解析的业务数据
     */
    public Map<String, Object> getParsed() {
        return parsed;
    }

    /**
     * 设置当前业务数据
     *
     * @param parsed 新的业务数据
     */
    public void setParsed(Map<String, Object> parsed) {
        this.parsed = parsed;
    }

    /**
     * 返回所属管线 ID
     *
     * @return pipelineId
     */
    public String getPipelineId() {
        return pipelineId;
    }

    /**
     * 设置所属管线 ID
     *
     * @param pipelineId 管线 ID
     */
    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }

    /**
     * 返回当前枚举管线阶段
     *
     * @return PipelineState
     */
    public PipelineState getState() {
        return state;
    }

    /**
     * 设定当前阶段
     *
     * @param state 枚举值
     */
    public void setState(PipelineState state) {
        this.state = state;
    }

    /**
     * 返回采集的时间戳
     *
     * @return epoch millis
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 设置时间戳
     *
     * @param timestamp 采集/接收时间戳
     */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 返回追踪 ID
     *
     * @return traceId
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * 设定追踪 ID
     *
     * @param traceId 追踪标识
     */
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    /**
     * 获取已记录的迹信息
     *
     * @return 迹信息列表（不可变视图）
     */
    public List<String> getTrace() {
        return trace;
    }

    /**
     * 返回数据所属主题列表
     *
     * @return 主题集合（可能为 null）
     */
    public Set<String> getTopics() {
        return topics;
    }

    /**
     * 设置数据所属主题列表
     *
     * @param topics 主题集合
     */
    public void setTopics(Set<String> topics) {
        this.topics = topics;
    }

    /**
     * 判断是否为日志型数据
     *
     * @return true 表示该数据源自日志管线
     */
    public boolean isLog() {
        return topics != null && topics.stream().anyMatch(t -> t.startsWith("log"));
    }

    /**
     * 追加一条迹信息
     *
     * @param entry 迹描述
     */
    public void addTrace(String entry) {
        this.trace.add(entry);
    }
}