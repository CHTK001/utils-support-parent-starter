package com.chua.common.support.ai.runtime;

import com.chua.common.support.ai.chat.ChatClient;

import java.util.Set;

/**
 * AI 运行时配置契约 —— 业务模块读取「实际生效的模型配置」与「可用对话客户端」的唯一入口。
 *
 * <p>业务模块（eduai 等）不应直接依赖任何框架的配置读取器、模型解析器或客户端注册表：
 * 这些都随部署形态变化（本地库、配置中心、远端服务）。本接口把它们收敛为
 * 「按用途提问」的窄契约，实现由上层框架（如 spring-support-ai-starter）注册为
 * Spring Bean。</p>
 *
 * <p><b>方法语义约定：</b></p>
 * <ul>
 *   <li>所有方法都不抛异常，配置缺失或后端不可用返回 {@code null} / 空值，
 *       调用方以「是否为空」作为降级判据；</li>
 *   <li>「生效」指按优先级合并后的结果（运行时配置 &gt; 兼容旧键 &gt; 静态配置），
 *       业务据此回显才与真实调用一致；</li>
 *   <li>{@link #chatClient(String)} 与 {@link #defaultChatClient()} 返回的都是可直接调用的
 *       实例，不要求调用方再判断内部是否已装配。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface AiRuntimeConfig {

    /**
     * 运行时配置项（如 {@code provider}、{@code default_model}）的原样值。
     *
     * <p>用于读取少量「无法收敛成固定方法」的调优参数；常规配置优先用本接口的语义方法。</p>
     *
     * @param group 配置分组
     * @param field 配置字段
     * @return 配置值；不存在时返回 {@code null}
     */
    String runtimeSetting(String group, String field);

    /**
     * 实际生效的默认对话模型（{@code provider:model} 形式，或后端约定的模型标识）。
     *
     * @param explicitModel 调用方指定的模型；传 {@code null} 表示取系统默认
     * @return 生效模型标识；无从解析时返回 {@code null}
     */
    String effectiveChatModel(String explicitModel);

    /**
     * 按厂商取已装配的对话客户端。
     *
     * @param provider 厂商标识
     * @return 对话客户端；该厂商未装配时返回 {@code null}
     */
    ChatClient chatClient(String provider);

    /**
     * 当前生效的默认对话客户端。
     *
     * @return 默认对话客户端；尚未装配时返回 {@code null}
     */
    ChatClient defaultChatClient();

    /**
     * 已装配的对话客户端所属厂商集合。
     *
     * @return 厂商集合；无可用客户端时返回空集合（不为 {@code null}）
     */
    Set<String> chatProviders();

    /**
     * 静态对话配置是否已启用。
     *
     * <p>用于区分「配置文件打开了开关」与「实际能用」：开关打开但缺密钥时，
     * 客户端不会被创建，业务应据此给出可操作的提示，而不是笼统地说「AI 不可用」。</p>
     *
     * @return true 表示静态配置已启用
     */
    boolean staticChatEnabled();

    /**
     * 静态配置的厂商标识。
     *
     * @return 厂商标识；未配置时返回 {@code null}
     */
    String staticChatProvider();

    /**
     * 静态配置的模型标识。
     *
     * @return 模型标识；未配置时返回 {@code null}
     */
    String staticChatModel();

    /**
     * 静态配置是否缺少密钥（已启用但未配置）。
     *
     * @return true 表示启用了静态对话配置但没有密钥
     */
    boolean staticChatKeyMissing();
}
