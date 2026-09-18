package com.chua.playwright.support.doubao;

/**
* 豆包 Web 端逆向协议常量。
*
* <p>集中维护豆包（Doubao / samantha）内部 SSE 聊天协议的关键常量，
* 包括认证 Cookie 名、端点、机器人_标识、事件类型、内容类型等。
*
* <p>参考 https://github.com/wangchuxiaoji-oss/doubao2api 逆向实现：
* <ul>
*   <li>认证：Cookie（{@code sessionid}、{@code ttwid}、{@code passport_csrf_token}）</li>
*   <li>chat 端点：{@code /chat/completion?aid=497858&device_platform=web}</li>
*   <li>多模态扩展 bot：{@link #EXTENSION_BOT_ID}</li>
*   <li>签名：a_bogus + msToken 由浏览器页面 fetch hook 自动注入</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public final class DoubaoConstants {

    /**
    * 豆包 Web 首页地址，用于加载页面并触发 获取 hook 注入签名。
    */
    public static final String HOME_URL = "https://www.doubao.com";

    /**
    * 聊天主端点基础路径。
    *
    * <p>豆包主用 {@code /samantha/chat/completion} 端点为 JSON 明文 SSE 协议，
    * 支持思考链（{@code block_type=10040} + {@code 10000}），为推荐主用端点。
    * 完整地址形如 {@code /samantha/chat/completion?aid=497858&device_platform=web...}，
    * 其中 {@code aid} 为豆包 PC Web 端应用 标识 固定常量。
    */
    public static final String CHAT_COMPLETION_PATH = "/samantha/chat/completion";

    /**
    * 豆包 PC Web 端应用 标识 固定常量。
    */
    public static final String AID = "497858";

    /**
    * 设备平台标识，固定为 web。
    */
    public static final String DEVICE_PLATFORM = "web";

    /**
    * 通用 机器人（当前统一服务默认 机器人）。
    *
    * <p>支持文件问答与多媒体生成。
    */
    public static final String DEFAULT_BOT_ID = "7338286299411103781";

    /**
    * 多模态扩展 机器人，图片 / 文件上传对话需使用此 机器人。
    */
    public static final String EXTENSION_BOT_ID = "7338286299411103781";

    /**
    * 认证 Cookie：主登录态标识。
    */
    public static final String COOKIE_SESSION_ID = "sessionid";

    /**
    * 认证 Cookie：设备绑定 令牌。
    */
    public static final String COOKIE_TTWID = "ttwid";

    /**
    * 认证 Cookie：CSRF 防护 令牌，同时作为 {编码 a-CSRF-令牌} 请求头。
    */
    public static final String COOKIE_CSRF_TOKEN = "passport_csrf_token";

    /**
    * 签名 令牌 Cookie 域前缀。
    */
    public static final String BYTEDANCE_DOMAIN = ".bytedance.com";

    /**
    * 豆包主域。
    */
    public static final String DOUBAO_DOMAIN = ".doubao.com";

    /**
    * 私有构造器，禁止实例化。
    */
    private DoubaoConstants() {
    }
}
