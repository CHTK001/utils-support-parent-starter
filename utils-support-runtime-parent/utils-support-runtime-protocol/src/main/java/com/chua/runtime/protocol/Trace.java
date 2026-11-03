package com.chua.runtime.protocol;

import com.chua.common.support.collection.wrap.Wraps;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 追踪 — 一个 追踪id 对应一整条调用链。
 *
 * <p>包含根 Span、所有 Span、起始时间。</p>
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
     * 追踪 标识
     */
    private String traceId;

    /**
     * 根 Span
     */
    private Span root;

    /**
     * 所有 Span（按开始时间排序）
     */
    @Builder.Default
    private List<Span> spans = Wraps.list();

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