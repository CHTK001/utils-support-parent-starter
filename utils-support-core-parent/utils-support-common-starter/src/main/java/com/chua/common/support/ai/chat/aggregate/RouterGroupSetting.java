// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.ai.chat.aggregate;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由组配置：路由策略 + 四种兜底行为 + 成员列表。
 *
 * <p>整个对象由 {@link RouterGroupChatClient} 内部消化，调用方只负责把配置交进来；
 * 字段名与前端配置面板一一对应，语义见各枚举的文档。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Data
public class RouterGroupSetting {

    /**
     * 路由策略（默认智能）
     */
    private Strategy strategy = Strategy.SMART;

    /**
     * 被限流时怎么做（默认保持原位，与「默认」等价）
     */
    private RateLimitPolicy onRateLimit = RateLimitPolicy.DEFAULT;

    /**
     * 延迟不出字时怎么做（默认一直等）
     */
    private SlowPolicy onSlow = SlowPolicy.DEFAULT;

    /**
     * 成员全都忙时怎么做（默认关闭与保持原位等价）
     */
    private BusyPolicy onBusy = BusyPolicy.DEFAULT;

    /**
     * 上下文按哪个口径取（默认最大模型的）
     */
    private ContextPolicy onContext = ContextPolicy.MAX;

    /**
     * 自定义上下文窗口（{@link ContextPolicy#CUSTOM} 时生效，单位 token）
     */
    private Integer contextWindow;

    /**
     * 延迟判定阈值（毫秒），为 null 时按 {@link SlowPolicy} 的预设
     */
    private Long slowMillis;

    /**
     * 全忙时最长等待（毫秒），为 null 时按 {@link BusyPolicy} 的预设
     */
    private Long busyMillis;

    /**
     * 智能策略：提示词超过多少字符就换到「上下文够大」的模型，0 表示不按长度换
     */
    private int longPromptChars = 4000;

    /**
     * 成员列表（顺序即「按顺序」策略的优先顺序）
     */
    private List<Member> members = new ArrayList<>();

    /**
     * 路由策略。
     */
    public enum Strategy {
        /**
         * 智能：按「价格 × 延迟 × 成功率」打分，重活派给强模型、轻活派给便宜模型
         */
        SMART("smart"),
        /**
         * 按顺序：永远先试列表第一个
         */
        ORDER("order"),
        /**
         * 用完再换：一直用当前这个，直到它被限流/报错才换下一个
         */
        UNTIL_EXHAUSTED("until_exhausted"),
        /**
         * 轮流：每个请求换一个成员
         */
        ROUND_ROBIN("round_robin"),
        /**
         * 最少使用：优先派给累计调用次数最少的成员
         */
        LEAST_USED("least_used"),
        /**
         * 到期前用完：离配额到期最近的先派出去，避免额度白白过期
         */
        EXPIRE_FIRST("expire_first"),
        /**
         * 免费优先：免费成员用尽才用付费成员
         */
        FREE_FIRST("free_first"),
        /**
         * 速度优先：优先派给实测首字延迟最低的成员
         */
        SPEED_FIRST("speed_first"),
        /**
         * 最便宜优先：优先派给价格最低的成员
         */
        CHEAPEST_FIRST("cheapest_first"),
        /**
         * 免费额度先到先用：免费成员按「额度先到期」的顺序使用
         */
        FREE_QUOTA_FIRST("free_quota_first"),
        /**
         * 按权重：按配置的权重随机分配
         */
        WEIGHTED("weighted"),
        /**
         * 手动：只调用当前选中的那一个，不自动切换
         */
        MANUAL("manual");

        /**
         * 配置里的写法
         */
        private final String code;

        Strategy(String code) {
            this.code = code;
        }

        /**
         * 配置字符串 → 枚举；未知值回落到 {@link #SMART}。
         *
         * @param code 配置值
         * @return 策略
         */
        public static Strategy of(String code) {
            if (code == null || code.isBlank()) {
                return SMART;
            }
            String value = code.trim().toLowerCase();
            for (Strategy s : values()) {
                if (s.code.equals(value) || s.name().equalsIgnoreCase(value)) {
                    return s;
                }
            }
            return SMART;
        }

        /**
         * 配置里的写法
         *
         * @return 短代码
         */
        public String code() {
            return code;
        }
    }

    /**
     * 被限流时怎么做。
     */
    public enum RateLimitPolicy {
        /**
         * 默认：该成员进入冷却，本次请求换下一个
         */
        DEFAULT("default", 0),
        /**
         * 保持原位：冷却时长按最短档，并且不跳顺序
         */
        KEEP_POSITION("keep_position", 0),
        /**
         * 排到最后：本次请求换下一个，且该成员排到顺序末尾
         */
        MOVE_TO_END("move_to_end", 0);

        /**
         * 配置里的写法
         */
        private final String code;

        /**
         * 预设冷却（毫秒），0 表示沿用冷却登记表的默认
         */
        private final long cooldownMs;

        RateLimitPolicy(String code, long cooldownMs) {
            this.code = code;
            this.cooldownMs = cooldownMs;
        }

        /**
         * 配置字符串 → 枚举；未知值回落到 {@link #DEFAULT}。
         *
         * @param code 配置值
         * @return 策略
         */
        public static RateLimitPolicy of(String code) {
            if (code == null || code.isBlank()) {
                return DEFAULT;
            }
            String value = code.trim().toLowerCase();
            for (RateLimitPolicy p : values()) {
                if (p.code.equals(value) || p.name().equalsIgnoreCase(value)) {
                    return p;
                }
            }
            return DEFAULT;
        }

        /**
         * 被限流后是否要把该成员排到顺序末尾
         *
         * @return true 表示排到最后
         */
        public boolean movesToEnd() {
            return this == MOVE_TO_END;
        }

        /**
         * 预设冷却时长
         *
         * @return 毫秒；0 表示沿用默认
         */
        public long cooldownMs() {
            return cooldownMs;
        }

        /**
         * 配置里的写法（与前端下拉取值一致）
         *
         * @return 短代码
         */
        public String code() {
            return code;
        }
    }

    /**
     * 延迟不出字（首字超时）怎么做。
     */
    public enum SlowPolicy {
        /**
         * 默认：一直等，不主动切断
         */
        DEFAULT("default", 0),
        /**
         * 一直等：与默认相同，显式表达
         */
        WAIT("wait", 0),
        /**
         * 30 秒
         */
        THIRTY_SECONDS("30s", 30_000L),
        /**
         * 1 分钟
         */
        ONE_MINUTE("1m", 60_000L),
        /**
         * 2 分钟
         */
        TWO_MINUTES("2m", 120_000L),
        /**
         * 自定义（取 {@link RouterGroupSetting#slowMillis}）
         */
        CUSTOM("custom", 0);

        /**
         * 配置里的写法
         */
        private final String code;

        /**
         * 预设阈值（毫秒），0 表示无限等待
         */
        private final long millis;

        SlowPolicy(String code, long millis) {
            this.code = code;
            this.millis = millis;
        }

        /**
         * 配置字符串 → 枚举；未知值回落到 {@link #DEFAULT}。
         *
         * @param code 配置值
         * @return 策略
         */
        public static SlowPolicy of(String code) {
            if (code == null || code.isBlank()) {
                return DEFAULT;
            }
            String value = code.trim().toLowerCase();
            for (SlowPolicy p : values()) {
                if (p.code.equals(value) || p.name().equalsIgnoreCase(value)) {
                    return p;
                }
            }
            return DEFAULT;
        }

        /**
         * 预设阈值
         *
         * @return 毫秒；0 表示无限等待
         */
        public long millis() {
            return millis;
        }

        /**
         * 配置里的写法（与前端下拉取值一致）
         *
         * @return 短代码
         */
        public String code() {
            return code;
        }
    }

    /**
     * 成员全都忙时怎么做（用于流式：切了又没有成员可接时）。
     */
    public enum BusyPolicy {
        /**
         * 默认：直接报错
         */
        DEFAULT("default", 0),
        /**
         * 关闭：与默认相同，显式表达
         */
        OFF("off", 0),
        /**
         * 等 1 分钟
         */
        ONE_MINUTE("1m", 60_000L),
        /**
         * 等 2 分钟
         */
        TWO_MINUTES("2m", 120_000L),
        /**
         * 等 4 分钟
         */
        FOUR_MINUTES("4m", 240_000L);

        /**
         * 配置里的写法
         */
        private final String code;

        /**
         * 最长等待（毫秒），0 表示不等待
         */
        private final long millis;

        BusyPolicy(String code, long millis) {
            this.code = code;
            this.millis = millis;
        }

        /**
         * 配置字符串 → 枚举；未知值回落到 {@link #DEFAULT}。
         *
         * @param code 配置值
         * @return 策略
         */
        public static BusyPolicy of(String code) {
            if (code == null || code.isBlank()) {
                return DEFAULT;
            }
            String value = code.trim().toLowerCase();
            for (BusyPolicy p : values()) {
                if (p.code.equals(value) || p.name().equalsIgnoreCase(value)) {
                    return p;
                }
            }
            return DEFAULT;
        }

        /**
         * 最长等待
         *
         * @return 毫秒；0 表示不等待
         */
        public long millis() {
            return millis;
        }

        /**
         * 配置里的写法（与前端下拉取值一致）
         *
         * @return 短代码
         */
        public String code() {
            return code;
        }
    }

    /**
     * 上下文窗口的口径。
     */
    public enum ContextPolicy {
        /**
         * 最大模型的
         */
        MAX("max"),
        /**
         * 最小模型的（保证任何成员都吃得下）
         */
        MIN("min"),
        /**
         * 自定义（取 {@link RouterGroupSetting#contextWindow}）
         */
        CUSTOM("custom");

        /**
         * 配置里的写法
         */
        private final String code;

        ContextPolicy(String code) {
            this.code = code;
        }

        /**
         * 配置字符串 → 枚举；未知值回落到 {@link #MAX}。
         *
         * @param code 配置值
         * @return 策略
         */
        public static ContextPolicy of(String code) {
            if (code == null || code.isBlank()) {
                return MAX;
            }
            String value = code.trim().toLowerCase();
            for (ContextPolicy p : values()) {
                if (p.code.equals(value) || p.name().equalsIgnoreCase(value)) {
                    return p;
                }
            }
            return MAX;
        }

        /**
         * 配置里的写法
         *
         * @return 短代码
         */
        public String code() {
            return code;
        }
    }

    /**
     * 成员：引用「已加入模型库」的某一行。
     *
     * @param providerId 成员模型所属供应商
     * @param model      成员模型名（与 {@code sys_ai_provider_model} 主键对应）
     * @param enabled    是否参与路由
     * @param weight     权重（按权重策略使用，默认 1）
     * @param effort     该成员的思考强度（reasoning effort），空表示用上游默认
     * @param free       是否免费（免费优先等策略用；null 表示按上游价格推断）
     * @param expireAt   额度到期时间（毫秒时间戳；到期前用完策略用，null 表示无到期）
     */
    public record Member(String providerId, String model, boolean enabled, int weight,
                         String effort, Boolean free, Long expireAt) {

        /**
         * 简易构造：启用、权重 1、无 effort。
         *
         * @param providerId 供应商
         * @param model      模型名
         * @return 成员
         */
        public static Member of(String providerId, String model) {
            return new Member(providerId, model, true, 1, null, null, null);
        }

        /**
         * 标签：{@code providerId:model}，用作日志与冷却键。
         *
         * @return 标签
         */
        public String label() {
            return providerId + ":" + model;
        }
    }
}
