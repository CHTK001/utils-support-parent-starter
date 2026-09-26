package com.chua.trae.support.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
     * 规范构造器：对两个映射组件做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。本配置为一次性装载、长期持有的
     * 共享状态，若直接持有 Jackson 反序列化产出的可变映射，调用方就地改写会串改全局路由。</p>
     *
     * <p>配置项在 JSON 中可整体缺省，{@link com.chua.trae.support.ChatClient} 亦显式判空
     * （{@code models() != null}、{@code mappings() != null}），故两个组件保留 {@code null} 语义；
     * 映射内的值允许为 {@code null}，故采用可空安全的不可变包装而非会拒绝 null 值的
     * {@link Map#copyOf(Map)}。</p>
     *
     * @param models   模型映射表
     * @param tiers    分档定义
     * @param fallback 降级配置
     * @param settings 运行时设置
     */
    public ModelConfig {
        models = models == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(models));
        tiers = tiers == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(tiers));
    }

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
    ) {

        /**
         * 规范构造器：对档位模型列表做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。本档位定义由
         * {@link ModelConfig} 统一持有，若直接持有反序列化产出的可变列表，
         * 逐档位改写会互相串扰。</p>
         *
         * <p>该列表在 JSON 中可缺省，保留 {@code null} 语义；元素允许为 {@code null}，
         * 故采用可空安全的不可变包装而非 {@link List#copyOf(List)}。</p>
         *
         * @param name        档位名称
         * @param models      该档位包含的模型列表
         * @param description 档位描述
         */
        public TierDef {
            models = models == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(models));
        }
    }

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
         * 规范构造器：对降级链映射做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。降级链映射是模型路由的核心配置，
         * 若直接持有反序列化产出的可变映射，调用方就地增删会静默改变实际降级行为。</p>
         *
         * <p>{@link com.chua.trae.support.ChatClient} 显式判空（{@code mappings() != null}）
         * 后按「无降级链」处理，组件保留 {@code null} 语义；映射内的值允许为 {@code null}，
         * 故采用可空安全的不可变包装而非会拒绝 null 值的 {@link Map#copyOf(Map)}。</p>
         *
         * @param autoFlag 是否启用自动降级
         * @param queueThresholdValue 排队阈值
         * @param tieredFlag 是否启用分档降级
         * @param raceFlag 是否同档竞速
         * @param fallbackModel 兜底模型
         * @param mappings 旧版降级链映射
         */
        public FallbackConfig {
            mappings = mappings == null
                    ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(mappings));
        }

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
