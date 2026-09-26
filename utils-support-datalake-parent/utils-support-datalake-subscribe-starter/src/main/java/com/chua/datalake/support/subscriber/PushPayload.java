package com.chua.datalake.support.subscriber;

import com.chua.datalake.support.model.DataEnvelope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 推送载荷。
 *
 * <p>由 {@link DataEnvelope} 派生：{@code offset} 取信封的时间戳（全序单调递增），
 * {@code data} 是管线当前阶段的业务数据，{@code envelope} 保留原始信封，
 * 便于订阅方读取 {@code traceId}/{@code pipelineId}/{@code state} 等元数据而不必再从 Map 里捞。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushPayload {

    /**
     * 订阅器 标识
     */
    private String subscriberId;

    /**
     * 偏移量
     */
    private long offset;

    /**
     * 业务数据
     */
    private Map<String, Object> data;

    /**
     * 原始信封
     */
    private DataEnvelope envelope;

    /**
     * 由信封构造某订阅器的推送载荷。
     *
     * @param subscriberId 订阅器标识
     * @param envelope     数据信封，非空
     * @return 推送载荷
     */
    public static PushPayload of(String subscriberId, DataEnvelope envelope) {
        return PushPayload.builder()
                .subscriberId(subscriberId)
                .offset(envelope.getTimestamp())
                .data(envelope.getParsed())
                .envelope(envelope)
                .build();
    }
}
