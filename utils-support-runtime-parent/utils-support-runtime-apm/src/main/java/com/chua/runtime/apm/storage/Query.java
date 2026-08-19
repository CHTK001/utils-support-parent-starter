package com.chua.runtime.apm.storage;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 查询条件 — 用于 {@link ApmStorage#queryTransmissions(Query)} 等方法。
 *
 * <p>所有字段为可选；空条件表示全量查询。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Accessors(chain = true)
public class Query {

    /** 起始时间（毫秒） */
    private Long startTime;

    /** 结束时间（毫秒） */
    private Long endTime;

    /** 限制返回条数 */
    private int limit = 100;

    /** 偏移 */
    private int offset = 0;

    /** traceId 精确匹配 */
    private String traceId;

    /** 源端点 host 模糊匹配 */
    private String sourceHost;

    /** 目标端点 host 模糊匹配 */
    private String targetHost;

    /** 协议过滤（HTTP/TCP/...） */
    private String protocol;

    /** 软件栈过滤（JEDIS/TOMCAT/...） */
    private String software;

    /** 状态过滤（OK/ERROR） */
    private String status;

    /** 只查询错误 */
    private boolean errorOnly;

    /** 全量查询（limit=100，无其他过滤） */
    public static Query all() {
        return new Query();
    }
}