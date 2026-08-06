package com.chua.runtime.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 追踪 — 一个 traceId 对应一整条调用链。
 *
 * <p>包含根 Span、所有 Span、起始时间、traceState（W3C 兼容）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trace {

    /**
     * 追踪 ID（W3C traceparent 标准）
     */
    private String traceId;

    /**
     * W3C traceState（多厂商 trace 串联）
     */
    private String traceState;

    /**
     * 根 Span
     */
    private Span root;

    /**
     * 所有 Span（按开始时间排序）
     */
    @Builder.Default
    private List<Span> spans = new ArrayList<>();

    /**
     * 追踪级属性（trace-level attributes）
     */
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>();

    /**
     * 追踪开始时间（毫秒）
     */
    private long startTime;

    /**
     * 追踪结束时间（毫秒）
     */
    private long endTime;

    /**
     * 是否已结束
     */
    @Builder.Default
    private boolean finished = false;
}