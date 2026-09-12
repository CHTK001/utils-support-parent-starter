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
   * mailnest 临时邮箱服务实现（付费）。
 *
 * <p>注册地址：https://mailnest.top</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("mailnest")
public class MailNestEmailProvider implements EmailProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器
    private static final String BASE = "https://mailnest.top/api/v1"; // 基础
    private static final String DEFAULT_PROJECT_CODE = "x-ai001"; // 默认project编码

    private final String apiKey; // api键
    private final String projectCode; // project编码

    /**
     * mailnestemail提供者。
     */
    public MailNestEmailProvider() {
        this.apiKey = resolveEnv("MAILNEST_API_KEY", "");
        this.projectCode = resolveEnv("MAILNEST_PROJECT_CODE", DEFAULT_PROJECT_CODE);
    }

    @Override
    public String name() {
        return "mailnest";
    }

    @Override
    public String createEmail() {
        if (apiKey.isBlank()) {
            log.warn("[MailNest] MAILNEST_API_KEY 未配置");
            return null;
        }
        try {
            // 先尝试购买共享邮箱
            String body = "{\"project_code\":\"" + projectCode + "\",\"count\":1}";
            String resp = HttpClientFactory.of(BASE + "/email/temporary/buy")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .post()
                    .getBodyString();
            JsonNode root = MAPPER.readTree(resp);
            if (root.isArray() && !root.isEmpty()) {
                String email = root.get(0).path("email").asText("");
                if (!email.isBlank()) {
                    log.info("[MailNest] 创建邮箱成功: {}", email);
                    return email;
                }
            }
            // 共享邮箱不足时，尝试购买独占邮箱
            resp = HttpClientFactory.of(BASE + "/email/exclusive/buy")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body("{\"count\":1}")
                    .post()
                    .getBodyString();
            root = MAPPER.readTree(resp);
            if (root.isArray() && !root.isEmpty()) {
                String email = root.get(0).path("email").asText("");
                if (!email.isBlank()) {
                    log.info("[MailNest] 创建独占邮箱成功: {}", email);
                    return email;
                }
            }
            log.warn("[MailNest] 创建邮箱失败: {}", resp);
            return null;
        } catch (Exception e) {
            log.warn("[MailNest] 创建邮箱异常: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<EmailInfo> fetchEmails(String email) {
        if (apiKey.isBlank() || email == null) {
            return Collections.emptyList();
        }
        try {
            String body = "{\"email\":\"" + email + "\"}";
            String resp = HttpClientFactory.of(BASE + "/email/receive")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .post()
                    .getBodyString();
            JsonNode root = MAPPER.readTree(resp);
            if (!root.isArray()) {
                return Collections.emptyList();
            }
            List<EmailInfo> result = new ArrayList<>();
            for (JsonNode msg : root) {
                result.add(EmailInfo.builder()
                        .subject(msg.path("subject").asText(null))
                        .body(msg.path("body").asText(null))
                        .createdAt(msg.path("created_at").asText(null))
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.warn("[MailNest] 收取邮件失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public EmailInfo fetchFirstEmail(String email) {
        List<EmailInfo> emails = fetchEmails(email);
        return emails.isEmpty() ? null : emails.get(0);
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
