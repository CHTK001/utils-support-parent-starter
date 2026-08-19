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

    /**
     * 创建 ExpressTrace 实例
     * @param time time
     * @param String String
     * @param String String
     */
    public ExpressTrace(String time, String context, String location) {
        this.time = time;
        this.context = context;
        this.location = location;
    }

    /** 获取Time */
    public String getTime() {
        return time;
    }

    /** 获取Context */
    public String getContext() {
        return context;
    }

    /** 获取Location */
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
