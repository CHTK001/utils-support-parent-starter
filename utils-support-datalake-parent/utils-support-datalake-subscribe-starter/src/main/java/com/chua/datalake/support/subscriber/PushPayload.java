package com.chua.datalake.support.subscriber;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 推送载荷。
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
     * 订阅器 ID
     */
    private String subscriberId;

    /**
     * offset
     */
    private long offset;

    /**
     * 业务数据
     */
    private Map<String, Object> data;
}