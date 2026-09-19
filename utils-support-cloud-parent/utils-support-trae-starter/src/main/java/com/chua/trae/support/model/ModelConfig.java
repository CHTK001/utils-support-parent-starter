package com.chua.trae.support.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 模型分档配置，承载模型映射、分档定义、降级策略与运行时设置。
 * 对应 resources/模型-配置.json。
 *
 * @param models 模型映射表，键 为模型别名，值 为模型条目
 * @param tiers 分档定义，键 为档位号（1-5），值 为档位配置
 * @param fallback 降级配置
 * @param settings 运行时设置
 * @author CH
 * @since 4.0.0.42
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelConfig(

    /**
     * 模型映射表
    */
    @JsonProperty("models") Map<String, ModelEntry> models,

    /**
     * 分档定义
    */
    @JsonProperty("tiers") Map<String, TierDef> tiers,

    /**
     * 降级配置
    */
    @JsonProperty("fallback") FallbackConfig fallback,

    /**
     * 运行时设置
    */
    @JsonProperty("settings") Settings settings
) {

    /**
     * 模型条目。
     *
     * @param function 函数类型，如 对话_v3
     * @param config_name 后端配置名，如 glm-5.2
     * @param category 模型分类
     * @param toolcallCompatible 是否支持工具调用
     * @param multimodal 是否多模态
     * @param reasoning 是否推理模型
     * @param tier 档位号（1-5）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelEntry(
        @JsonProperty("function") String function,
        @JsonProperty("config_name") String config_name,
        @JsonProperty("category") String category,
        @JsonProperty("toolcall_compatible") Boolean toolcallCompatible,
        @JsonProperty("multimodal") Boolean multimodal,
        @JsonProperty("reasoning") Boolean reasoning,
        @JsonProperty("tier") Integer tier
    ) {
        /**
         * 获取后端配置名，缺省回退到 function。
         *
         * @return 配置名，不可为 空
         */
        public String configName() {
            return config_name != null ? config_name : function;
        }
    }

    /**
     * 档位定义。
     *
     * @param name 档位名称（旗舰/强力/中等/轻量/最轻）
     * @param models 该档位包含的模型列表
     * @param description 档位描述
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TierDef(
        @JsonProperty("name") String name,
        @JsonProperty("models") List<String> models,
        @JsonProperty("description") String description
    ) {}

    /**
     * 降级配置。
     *
     * @param autoFlag 是否启用自动降级
     * @param queueThresholdValue 排队阈值
     * @param tieredFlag 是否启用分档降级
     * @param raceFlag 是否同档竞速
     * @param fallbackModel 兜底模型
     * @param mappings 旧版降级链映射
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FallbackConfig(
        @JsonProperty("autoFallback") Boolean autoFlag,
        @JsonProperty("queueThreshold") Integer queueThresholdValue,
        @JsonProperty("tieredFallback") Boolean tieredFlag,
        @JsonProperty("raceWithinTier") Boolean raceFlag,
        @JsonProperty("fallbackModel") String fallbackModel,
        @JsonProperty("mappings") Map<String, List<String>> mappings
    ) {
        /**
         * 是否启用自动降级。
         *
         * @return true 表示启用
         */
        public boolean autoFallback() { return autoFlag != null && autoFlag; }

        /**
         * 是否启用分档降级。
         *
         * @return true 表示启用
         */
        public boolean tieredFallback() { return tieredFlag != null && tieredFlag; }

        /**
         * 是否同档竞速。
         *
         * @return true 表示启用
         */
        public boolean raceWithinTier() { return raceFlag != null && raceFlag; }

        /**
         * 排队阈值，默认 300。
         *
         * @return 阈值
         */
        public int queueThreshold() { return queueThresholdValue != null ? queueThresholdValue : 300; }

        /**
         * 兜底模型，默认 glm-5。
         *
         * @return 兜底模型名
         */
        public String fallbackModel() { return fallbackModel != null ? fallbackModel : "glm-5"; }
    }

    /**
     * 运行时设置。
     *
     * @param autoContinue 是否自动续写
     * @param maxContinues 最大续写次数
     * @param truncationTextThreshold 截断检测文本阈值
     * @param truncationSimilarityThreshold 截断相似度阈值
     * @param maxTokensLimit 最大 令牌 上限
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Settings(
        @JsonProperty("autoContinue") Boolean autoContinue,
        @JsonProperty("maxContinues") Integer maxContinues,
        @JsonProperty("truncationTextThreshold") Integer truncationTextThreshold,
        @JsonProperty("truncationSimilarityThreshold") Double truncationSimilarityThreshold,
        @JsonProperty("maxTokensLimit") Integer maxTokensLimit
    ) {}
}
