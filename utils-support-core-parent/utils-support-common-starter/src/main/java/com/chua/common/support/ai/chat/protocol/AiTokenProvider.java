package com.chua.common.support.ai.chat.protocol;

import java.util.List;
import java.util.Map;

/**
* AI 令牌提供者接口 — 提供令牌的存储、校验、增删改查能力。
*
* <p>{@link AiTokenServerFilter} 通过此接口获取令牌数据进行 Bearer Token 校验。
* {@link com.chua.common.support.ai.chat.aggregate.AggregateChatClient} 可以持有此接口实例，
* 将令牌管理与 AI 客户端聚合在一起。</p>
*
* <p>内置实现：</p>
* <ul>
*   <li>{@link FileAiTokenProvider} — 基于文本文件的实现，支持自动监听热加载</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public interface AiTokenProvider {

    /**
    * 校验令牌是否有效。
    *
    * @param tokenValue 令牌值
    * @return 有效返回 true
     */
    default boolean validate(String tokenValue) {
        return getValidToken(tokenValue) != null;
    }

    /**
    * 校验令牌并返回 AiToken 对象。
    *
    * @param tokenValue 令牌值
    * @return AiToken 对象，无效返回 null
     */
    AiToken getValidToken(String tokenValue);

    /**
    * 获取所有令牌（不可变视图）。
    *
    * @return token → AiToken 映射
     */
    Map<String, AiToken> allTokens();

    /**
    * 获取令牌总数。
    *
    * @return 令牌数量
     */
    int count();

    // ======================== 增删改 ========================

    /**
    * 添加或更新令牌。
    *
    * @param token AiToken 对象
     */
    void put(AiToken token);

    /**
    * 批量添加或更新令牌。
    *
    * @param tokens 令牌列表
     */
    void putAll(List<AiToken> tokens);

    /**
    * 移除令牌。
    *
    * @param tokenValue 令牌值
    * @return 被移除的令牌，不存在返回 null
     */
    AiToken remove(String tokenValue);

    /**
    * 清空所有令牌。
     */
    void clear();
}
