package com.chua.common.support.ai.chat;

/**
 * 决策客户端使用的协议端点。
 *
 * <p>同一个 {@link DecisionsClient} 可以在两种决策协议之间切换：
 * <ul>
 *   <li>{@link #OPENAI} — OpenAI 兼容协议的 {@code POST /v1/decisions}，模型 {@code gpt-6-luna}，
 *       问题类型 {@code predicate} / {@code choice} / {@code score}，答案为列表</li>
 *   <li>{@link #SYSTEMONE} — TypeSafe SystemOne 协议的 {@code POST /v1/systemone}，
 *       模型 {@code jev-latest}，问题类型 {@code noul} / {@code choice} / {@code score}，
 *       答案按问题名分发，使用 {@code A}~{@code Z} 与 {@code 0}~{@code 9}
       为答案标签</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum DecisionsProtocol {

    /**
     * OpenAI 协议的 /v1/decisions 端点
     */
    OPENAI("/v1/decisions"),

    /**
     * SystemOne 协议的 /v1/systemone 端点：模型 {@code jev-latest}，
     * 问题类型 {@code noul} / {@code choice} / {@code score}，
     * 答案按问题名分发，使用 {@code A}~{@code Z} 与 {@code 0}~{@code 9} 为答案标签
     */
    SYSTEMONE("/v1/systemone");

    /**
     * 协议端点路径
     */
    private final String path;

    DecisionsProtocol(String path) {
        this.path = path;
    }

    /**
     * 获取协议端点路径
     *
     * @return 路径
     */
    public String path() {
        return path;
    }
}
