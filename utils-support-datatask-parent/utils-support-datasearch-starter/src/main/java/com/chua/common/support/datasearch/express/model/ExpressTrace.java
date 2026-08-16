package com.chua.common.support.datasearch.express.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快递物流轨迹节点模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ExpressTrace {

    /**
     * 轨迹时间
     */
    private final String time;

    /**
     * 轨迹描述（如：已签收 / 运输中 / 派送中）
     */
    private final String context;

    /**
     * 轨迹地点（部分数据源可能为空）
     */
    private final String location;

    public ExpressTrace(String time, String context, String location) {
        this.time = time;
        this.context = context;
        this.location = location;
    }

    public String getTime() {
        return time;
    }

    public String getContext() {
        return context;
    }

    public String getLocation() {
        return location;
    }

    /**
     * 转换为 Map 用于 JSON 序列化
     *
     * @return Map 表示
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("time", time);
        map.put("context", context);
        map.put("location", location);
        return map;
    }
}
