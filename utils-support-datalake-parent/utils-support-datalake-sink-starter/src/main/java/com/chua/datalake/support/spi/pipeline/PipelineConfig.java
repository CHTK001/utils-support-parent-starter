package com.chua.datalake.support.spi.pipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管线配置模型，描述整条 pipeline 的阶段与 action。
 *
 * <p>配置结构为 JSON-DSL 解析后的 results：</p>
 * <pre>{@code
 * {
 *   "id": "pipeline-001",
 *   "stages": {
 *     "default": {
 *       "filter": [{"name": "null-filter"}],
 *       "parser": [{"name": "json"}],
 *       "sink": [
 *         {"type": "jdbc"},
 *         {"type": "realtime"}
 *       ]
 *     }
 *   }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineConfig {

    /**
     * 管线唯一标识
     */
    private String id;

    /**
     * stage → action 映射；key "default" 为默认路由
     */
    private Map<String, PipelineStageConfig> stages = new HashMap<>();

    /**
     * 返回无信息时的空配置
     */
    public static PipelineConfig empty() {
        PipelineConfig cfg = new PipelineConfig();
        cfg.setStages(Collections.singletonMap("default", new PipelineStageConfig()));
        return cfg;
    }

    /**
     * 单阶段配置 = {"filter": [], "parser": [], "cleaner": [], "standardizer": [], "sink": []}
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineStageConfig {

        /**
         * 过滤器列表
         */
        private List<Map<String, Object>> filter = Collections.emptyList();

        /**
         * 解析器列表
         */
        private List<Map<String, Object>> parser = Collections.emptyList();

        /**
         * 清洗器列表
         */
        private List<Map<String, Object>> cleaner = Collections.emptyList();

        /**
         * 标准化器列表
         */
        private List<Map<String, Object>> standardizer = Collections.emptyList();

        /**
         * 下沉 sink 列表
         */
        private List<Map<String, Object>> sink = Collections.emptyList();
    }
}