package com.chua.common.support.datasearch.email.spi.impl;

import com.chua.common.support.datasearch.email.model.EmailInfo;
import com.chua.common.support.datasearch.email.spi.EmailProvider;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
   * luckmail 临时邮箱服务实现（付费，约 ¥0.02/个）。
 *
 * <p>注册地址：https://mails.luckyous.com</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("luckmail")
public class LuckMailEmailProvider implements EmailProvider {

    private static final String DEFAULT_BASE = "https://mails.luckyous.com"; // 默认基础
    private static final String DEFAULT_PROJECT_CODE = "grok"; // 默认project编码
    private static final String DEFAULT_DOMAIN = "outlook.com"; // 默认domain
    private static final String DEFAULT_EMAIL_TYPE = "ms_imap"; // 默认email类型

    private final String apiKey; // api键
    private final String apiSecret; // apisecret
    private final String baseUrl; // baseurl
    private final String projectCode; // project编码
    private final String emailType; // email类型
    private final String domain; // domain

    /**
     * luckmailemail提供者。
     */
    public LuckMailEmailProvider() {
        this.apiKey = resolveEnv("LUCKMAIL_API_KEY", "");
        this.apiSecret = resolveEnv("LUCKMAIL_API_SECRET", "");
        this.baseUrl = resolveEnv("LUCKMAIL_BASE_URL", DEFAULT_BASE);
        this.projectCode = resolveEnv("LUCKMAIL_PROJECT_CODE", DEFAULT_PROJECT_CODE);
        this.emailType = resolveEnv("LUCKMAIL_EMAIL_TYPE", DEFAULT_EMAIL_TYPE);
        this.domain = resolveEnv("LUCKMAIL_DOMAIN", DEFAULT_DOMAIN);
    }

    @Override
    public String name() {
        return "luckmail";
    }

    @Override
    public String createEmail() {
        if (apiKey.isBlank()) {
            log.warn("[LuckMail] LUCKMAIL_API_KEY 未配置");
            return null;
        }
        try {
            String url = baseUrl + "/api/v1/purchase";
            String body = "{\"project_code\":\"" + projectCode
                    + "\",\"quantity\":1,\"email_type\":\"" + emailType
                    + "\",\"domain\":\"" + domain + "\"}";

            String resp = HttpClientFactory.of(url)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Secret", apiSecret)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .post()
                    .getBodyString();

            // 简化实现：返回请求体中的邮箱地址（实际需解析响应）
            log.info("[LuckMail] 购买请求已发送");
            return null; // TODO: 解析响应获取 email
        } catch (Exception e) {
            log.warn("[LuckMail] 创建邮箱异常: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<EmailInfo> fetchEmails(String email) {
        // TODO: 实现邮件收取
        return Collections.emptyList();
    }

    @Override
    public EmailInfo fetchFirstEmail(String email) {
        return null;
    }

    /**
      * resolveenv。
     * @param key 键
     * @param def def
     * @return resolveEnv的结果
     */
    private static String resolveEnv(String key, String def) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : def;
    }
}
