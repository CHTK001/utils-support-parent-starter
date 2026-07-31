package com.chua.datalake.support.spi.pipeline;

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
     * 空参构造
     */
    public PipelineConfig() {
    }

    /**
     * 返回管线 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 设置管线 ID
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * 获取所有 stage 配置
     */
    public Map<String, PipelineStageConfig> getStages() {
        return stages;
    }

    /**
     * 设置 stage 配置
     */
    public void setStages(Map<String, PipelineStageConfig> stages) {
        this.stages = stages;
    }

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
         * 下sink列表
         */
        private List<Map<String, Object>> sink = Collections.emptyList();

        public List<Map<String, Object>> getFilter() {
            return filter;
        }

        public void setFilter(List<Map<String, Object>> filter) {
            this.filter = filter;
        }

        public List<Map<String, Object>> getParser() {
            return parser;
        }

        public void setParser(List<Map<String, Object>> parser) {
            this.parser = parser;
        }

        public List<Map<String, Object>> getCleaner() {
            return cleaner;
        }

        public void setCleaner(List<Map<String, Object>> cleaner) {
            this.cleaner = cleaner;
        }

        public List<Map<String, Object>> getStandardizer() {
            return standardizer;
        }

        public void setStandardizer(List<Map<String, Object>> standardizer) {
            this.standardizer = standardizer;
        }

        public List<Map<String, Object>> getSink() {
            return sink;
        }

        public void setSink(List<Map<String, Object>> sink) {
            this.sink = sink;
        }
    }
}