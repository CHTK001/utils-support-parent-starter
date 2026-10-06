package com.chua.common.support.ai.rule;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Objects;

/**
 * 规则类型。
 *
 * <p>规则类型是 {@link Rule} 实现与 {@link RuleBuilder} 实现的装配键：
 * 同一类型下 Builder 与 Rule 一一对应，{@link RuleSet} 内不允许混用不同类型。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public enum RuleType {

    /**
     * 自然规则：条件 / 动作均在进程内求值，不依赖外部 AI 服务。
     */
    NATIVE,

    /**
     * AI 决策规则：条件由 AI 概率决策自动求值，默认走 laya 本地推理。
     */
    AI;

    /**
     * 按本类型获取规则构造器。
     *
     * <p>SPI 初始化：无参构造 + 后续 {@link RuleBuilder#id(String)} 指定标识。
     * 实现不存在时抛 {@link RuleException}，而不是返回 null 让调用方空指针。</p>
     *
     * @return 规则构造器
     * @throws RuleException 未找到对应类型的构造器实现时
     */
    public RuleBuilder builder() {
        return builder(null);
    }

    /**
     * 按本类型获取规则构造器。
     *
     * <p>SPI 初始化：优先用 {@code (String id)} 单参构造器，
     * 缺失时退回无参构造 + {@link RuleBuilder#id(String)}。
     * 两种路径最终的标识都以入参为准。</p>
     *
     * @param id 规则标识，可为 null（后续用 {@link RuleBuilder#id(String)} 指定）
     * @return 规则构造器
     * @throws RuleException 未找到对应类型的构造器实现或类型不匹配时
     */
    public RuleBuilder builder(String id) {
        String key = name().toLowerCase();
        RuleBuilder builder = null;
        if (id != null && !id.isBlank()) {
            builder = ServiceProvider.of(RuleBuilder.class).getNewExtension(key, id);
            if (builder == null) {
                builder = ServiceProvider.of(RuleBuilder.class).getNewExtension(key);
            }
        } else {
            builder = ServiceProvider.of(RuleBuilder.class).getNewExtension(key);
        }
        if (builder == null) {
            throw new RuleException("未找到 " + this + " 规则构造器"
                    + "，请确保 classpath 包含对应实现");
        }
        if (builder.type() != this) {
            throw new RuleException("规则构造器类型不匹配: 期望 " + this + "，实际 " + builder.type());
        }
        if (id != null && !id.isBlank()) {
            builder.id(id);
        }
        return builder;
    }

    /**
     * 按本类型与底座获取规则构造器。
     *
     * <p>底座是同一规则类型下的 SPI 实现名，例如 AI 规则默认走 {@code laya}。</p>
     *
     * @param id       规则标识，可为 null
     * @param provider 底座名称，可为 null（使用类型默认实现）
     * @return 规则构造器
     * @throws RuleException 未找到对应构造器实现或类型不匹配时
     */
    public RuleBuilder builder(String id, String provider) {
        Objects.requireNonNull(provider, "provider 不能为 null");
        if (provider.isBlank()) {
            return builder(id);
        }
        RuleBuilder builder = ServiceProvider.of(RuleBuilder.class).getNewExtension(provider, id);
        if (builder == null) {
            builder = ServiceProvider.of(RuleBuilder.class).getNewExtension(provider);
        }
        if (builder == null) {
            throw new RuleException("未找到 " + this + " 规则构造器(provider=" + provider + ")"
                    + "，请确保 classpath 包含对应实现");
        }
        if (builder.type() != this) {
            throw new RuleException("规则构造器类型不匹配: 期望 " + this + "，实际 " + builder.type());
        }
        if (id != null && !id.isBlank()) {
            builder.id(id);
        }
        return builder;
    }
}
