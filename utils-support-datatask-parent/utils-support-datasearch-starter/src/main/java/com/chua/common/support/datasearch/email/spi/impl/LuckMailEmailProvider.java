package com.chua.common.support.datasearch.email.spi.impl;

import com.chua.common.support.datasearch.email.model.EmailInfo;
import com.chua.common.support.datasearch.email.spi.EmailProvider;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LuckMail 临时邮箱服务实现（付费，约 ¥0.02/个）。
 *
 * <p>注册地址：https://mails.luckyous.com</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("luckmail")
public class LuckMailEmailProvider implements EmailProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE = "https://mails.luckyous.com";
    private static final String DEFAULT_PROJECT_CODE = "grok";
    private static final String DEFAULT_DOMAIN = "outlook.com";
    private static final String DEFAULT_EMAIL_TYPE = "ms_imap";

    private final String apiKey;
    private final String apiSecret;
    private final String baseUrl;
    private final String projectCode;
    private final String emailType;
    private final String domain;

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
            // 使用 LuckMail SDK 或 REST API 创建邮箱
            // 这里使用 REST API 方式
            String url = baseUrl + "/api/v1/purchase";
            String body = "{\"project_code\":\"" + projectCode
                    + "\",\"quantity\":1,\"email_type\":\"" + emailType
                    + "\",\"domain\":\"" + domain + "\"}";

            String resp = HttpClientFactory.of(url)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Secret", apiSecret)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .getBodyString();
            JsonNode root = MAPPER.readTree(resp);
            JsonNode purchases = root.path("purchases");
            if (purchases.isArray() && !purchases.isEmpty()) {
                String email = purchases.get(0).path("email_address").asText("");
                if (!email.isBlank()) {
                    log.info("[LuckMail] 创建邮箱成功: {}", email);
                    return email;
                }
            }
            log.warn("[LuckMail] 创建邮箱失败: {}", resp);
            return null;
        } catch (Exception e) {
            log.warn("[LuckMail] 创建邮箱异常: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<EmailInfo> fetchEmails(String email) {
        if (apiKey.isBlank() || email == null) {
            return Collections.emptyList();
        }
        try {
            String url = baseUrl + "/api/v1/inbox/" + email + "/messages";
            String resp = HttpClientFactory.of(url)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Secret", apiSecret)
                    .get()
                    .getBodyString();
            JsonNode root = MAPPER.readTree(resp);
            JsonNode messages = root.path("data");
            if (!messages.isArray()) {
                return Collections.emptyList();
            }
            List<EmailInfo> result = new ArrayList<>();
            for (JsonNode msg : messages) {
                result.add(EmailInfo.builder()
                        .id(msg.path("id").asText(null))
                        .from(msg.path("from").asText(null))
                        .subject(msg.path("subject").asText(null))
                        .body(msg.path("body").asText(null))
                        .html(msg.path("html").asText(null))
                        .createdAt(msg.path("created_at").asText(null))
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.warn("[LuckMail] 收取邮件失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public EmailInfo fetchFirstEmail(String email) {
        List<EmailInfo> emails = fetchEmails(email);
        return emails.isEmpty() ? null : emails.get(0);
    }

    private static String resolveEnv(String key, String def) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : def;
    }
}
