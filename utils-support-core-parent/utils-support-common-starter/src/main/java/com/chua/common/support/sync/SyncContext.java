package com.chua.common.support.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * 同步上下文
 * <p>同步管道中流转的数据载体，包含一行（条）数据及其元信息。</p>
 *
 * <p>数据流向：Input 产生 → Sink 缓冲 → Output 消费。</p>
 *
 * @author CH
 * @since 2026/07/28
 */
@NullUnmarked
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncContext implements Serializable {

    /**
     * 输入源标识
     */
    private String inputId;

    /**
     * 数据行（字段名 → 字段值）
     */
    @Builder.Default
    private Map<String, Object> data = new LinkedHashMap<>();

    /**
     * 数据对应的位点
     */
    private Position position;

    /**
     * 事件类型（INSERT / UPDATE / DELETE / SNAPSHOT）
     */
    @Builder.Default
    private String eventType = "SNAPSHOT";

    /**
     * 数据产生时间（毫秒时间戳）
     */
    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    /**
     * 扩展属性
     */
    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();

    /**
     * 创建同步上下文
     *
     * @param inputId 输入源标识
     * @param data    数据行
     * @return 同步上下文实例
     */
    public static SyncContext of(String inputId, Map<String, Object> data) {
        return SyncContext.builder()
                .inputId(inputId)
                .data(data)
                .build();
    }
}
