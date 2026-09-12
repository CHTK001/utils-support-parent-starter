package com.chua.playwright.support.qwen;

/**
   * 通义千问 (通义千问) Web 反向代理协议常量。
 *
 * <p>基于 Qwen2API 逆向实现，参考 https://github.com/Rfym21/Qwen2API
 *
 * <p>认证：Cookie（{@code token} JWT + {@code ssxmod_itna} 指纹）
 * <br>聊天端点：{@code /api/v2/chats/new} 创建会话 → {@code /api/v2/chat/completions?chat_id=xxx} 发送消息
 * <br>响应：SSE 流式，{@code phase} 字段区分思考/回答
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class QwenConstants {

    /**
     * 通义千问首页地址，用于加载页面并触发指纹 JS 注入 ssxmod_itna。
     */
    public static final String HOME_URL = "https://chat.qwen.ai";

    /**
     * 创建新会话端点。
     *
     * <p>POST 请求，返回 {@code chat_id}，后续聊天需要此 ID。
     */
    public static final String CHAT_NEW_PATH = "/api/v2/chats/new";

    /**
     * 聊天消息端点。
     *
     * <p>POST 请求，需携带 {@code chat_id} 查询参数，返回 SSE 流式响应。
     */
    public static final String CHAT_COMPLETION_PATH = "/api/v2/chat/completions";

    /**
     * 认证 Cookie 名称：JWT 登录令牌。
     */
    public static final String COOKIE_TOKEN = "token";

    /** 创建 通义千问常量 实例 */
    private QwenConstants() {
    }
}