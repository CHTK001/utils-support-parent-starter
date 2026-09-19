package com.chua.datalake.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据信封，封装流转中的业务数据与元数据。
 *
 * <p>每条数据进入管道后都会被包装为 DataEnvelope 在管线中流转。
 * 管线各阶段（过滤器、Parser、清洁剂、Standardizer、Sink）通过读取和修改
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
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataEnvelope {

    /**
     * 当前阶段的业务数据，核心存储结构为 {@code Map<String,Object>}
     */
    private Map<String, Object> parsed;

    /**
     * 所属管线 标识，同一管线同结构在这条流上处理/分析
     */
    private String pipelineId;

    /**
     * 当期所处的管线阶段状态
     */
    private PipelineState state;

    /**
     * 受理时间戳（毫秒），用作数据排序的 monotonically 增加 偏移量
     */
    private long timestamp;

    /**
     * 追踪 标识，可观察性标识
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
     * 以指定业务数据创建 Envelope
     *
     * @param parsed 当前业务数据（非空）
     */
    public DataEnvelope(Map<String, Object> parsed) {
        this.parsed = parsed;
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
