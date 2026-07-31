package com.chua.datalake.support.subscriber;

import java.util.Map;

/**
 * 推送载荷。
 *
 * @author CH
 * @since 4.0.0.43
 */
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

    public PushPayload() {
    }

    public PushPayload(String subscriberId, long offset, Map<String, Object> data) {
        this.subscriberId = subscriberId;
        this.offset = offset;
        this.data = data;
    }

    public String getSubscriberId() {
        return subscriberId;
    }

    public void setSubscriberId(String subscriberId) {
        this.subscriberId = subscriberId;
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}