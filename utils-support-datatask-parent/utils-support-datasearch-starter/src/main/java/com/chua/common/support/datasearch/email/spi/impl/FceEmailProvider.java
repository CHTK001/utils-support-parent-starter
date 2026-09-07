package com.chua.common.support.datasearch.email.spi.impl;

import com.chua.common.support.datasearch.email.model.EmailInfo;
import com.chua.common.support.datasearch.email.spi.EmailProvider;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FreeCustom.Email (FCE) 临时邮箱服务实现。
 *
 * <p>纯 REST API，无需浏览器。注册地址：https://www.freecustom.email/auth</p>
 *
 * <p>API 流程：</p>
 * <ol>
 *   <li>POST /v1/inboxes {"inbox": "随机前缀@域名"} → 注册地址</li>
 *   <li>GET  /v1/inboxes/{inbox}/messages → 收取邮件列表</li>
 * </ol>
 *
 * <p>域名池：{@code ditapi.info} / {@code fce.email}</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("fce")
public class FceEmailProvider implements EmailProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_URL = "https://api2.freecustom.email/v1";

    private static final String[] DOMAINS = {"ditapi.info", "fce.email"};

    /** 每次实例使用独立前缀计数器，避免并发冲突 */
    private final AtomicInteger counter = new AtomicInteger(0);

    /** API Key（从系统属性或环境变量读取） */
    private final String apiKey;

    public FceEmailProvider() {
        this.apiKey = resolveApiKey();
    }

    @Override
    public String name() {
        return "fce";
    }

    @Override
    public String createEmail() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[FceEmail] FCE_API_KEY 未配置，无法创建邮箱");
            return null;
        }
        String prefix = randomPrefix(10);
        String domain = DOMAINS[new java.util.Random().nextInt(DOMAINS.length)];
        String email = prefix + "@" + domain;
        try {
            String body = HttpClientFactory.of(BASE_URL + "/inboxes")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post("{\"inbox\":\"" + email + "\"}")
                    .getBodyString();
            JsonNode root = MAPPER.readTree(body);
            if (root.path("success").asBoolean(false)) {
                log.info("[FceEmail] 创建邮箱成功: {}", email);
                return email;
            }
            log.warn("[FceEmail] 创建邮箱失败: {}", body);
            return null;
        } catch (Exception e) {
            log.warn("[FceEmail] 创建邮箱异常: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<EmailInfo> fetchEmails(String email) {
        if (email == null || apiKey == null || apiKey.isBlank()) {
            return new ArrayList<>();
        }
        try {
            String url = BASE_URL + "/inboxes/" + java.net.URLEncoder.encode(email, "UTF-8") + "/messages";
            String body = HttpClientFactory.of(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .get()
                    .getBodyString();
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return new ArrayList<>();
            }
            List<EmailInfo> result = new ArrayList<>();
            for (JsonNode msg : data) {
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
            log.warn("[FceEmail] 收取邮件失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static String randomPrefix(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String resolveApiKey() {
        String key = System.getProperty("fce.api.key");
        if (key != null && !key.isBlank()) return key;
        key = System.getenv("FCE_API_KEY");
        return key;
    }
}
