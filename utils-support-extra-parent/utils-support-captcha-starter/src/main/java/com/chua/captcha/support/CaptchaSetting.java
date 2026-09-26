package com.chua.captcha.support;

import lombok.Builder;
import lombok.Data;

/**
 * 验证码解析服务配置
 * <p>
 * 用于配置验证码解析服务（如 captcha运行、yescaptcha）的连接参数，
 * 包括 API 令牌、服务地址、超时时间等。
 * </p>
 *
 * @author CH
 * @since 2026-03-14
 */
@Data
@Builder
public class CaptchaSetting {

    /**
     * yescaptcha API 服务地址
     */
    public static final String YESCAPTCHA_API_URL = "https://api.yescaptcha.com";

    /**
     * captcha运行 API 服务地址
     */
    public static final String CAPTCHA_RUN_API_URL = "https://api.captcha-run.com";

    /**
     * API 鉴权令牌（从 yescaptcha.com 或 captcha-运行.com 获取）
     */
    private String apiToken;

    /**
     * API 服务地址；留空时由各解析器使用自己的缺省端点（见 {@link #YESCAPTCHA_API_URL}、
     * {@link #CAPTCHA_RUN_API_URL}），避免跨服务商打到错误主机
     */
    private String apiUrl;

    /**
     * 连接超时时间（毫秒），默认 30000ms
     */
    @Builder.Default
    /**
     * 连接超时
    */
    private long connectTimeout = 30000;

    /**
     * 读取超时时间（毫秒），默认 30000ms
     */
    @Builder.Default
    /**
     * 读取超时
    */
    private long readTimeout = 30000;

    /**
     * 取 API 服务地址，未配置时回落到解析器自带的缺省端点。
     *
     * @param fallback 解析器的缺省端点
     * @return 实际使用的服务地址
     */
    public String apiUrlOr(String fallback) {
        return apiUrl == null || apiUrl.isBlank() ? fallback : apiUrl;
    }

    /**
     * 校验并返回 API 令牌。
     *
     * @return API 令牌
     * @throws IllegalStateException 令牌未配置时抛出，避免把 null 当作 clientKey 发给服务商
     */
    public String requireApiToken() {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException("CaptchaSetting.apiToken 未配置，无法调用验证码解析服务");
        }
        return apiToken;
    }

    /**
     * 从 JVM 参数或环境变量构建配置，供 SPI 无参实例化时使用。
     *
     * <p>配置键依次为 {@code captcha.api-token}、{@code captcha.api-url}、
     * {@code captcha.connect-timeout}、{@code captcha.read-timeout}，
     * 未给出时回落同名大写环境变量（如 {@code CAPTCHA_API_TOKEN}）。</p>
     *
     * @param defaultApiUrl 未配置地址时使用的缺省端点
     * @return 配置实例
     */
    public static CaptchaSetting fromEnvironment(String defaultApiUrl) {
        CaptchaSettingBuilder builder = builder().apiUrl(property("captcha.api-url", defaultApiUrl));
        String token = property("captcha.api-token", null);
        if (token != null) {
            builder.apiToken(token);
        }
        String connect = property("captcha.connect-timeout", null);
        if (connect != null) {
            builder.connectTimeout(parseMillis(connect));
        }
        String read = property("captcha.read-timeout", null);
        if (read != null) {
            builder.readTimeout(parseMillis(read));
        }
        return builder.build();
    }

    /**
     * 读取系统属性，缺失时回落环境变量
     *
     * @param key 配置键
     * @param fallback 缺省值
     * @return 配置值，两者皆缺失时为 {@code null}
     */
    private static String property(String key, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key.replace('.', '_').toUpperCase());
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    /**
     * 解析毫秒配置，非法值按内置默认处理
     *
     * @param text 配置文本
     * @return 毫秒数
     */
    private static long parseMillis(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 30000L;
        }
    }
}
