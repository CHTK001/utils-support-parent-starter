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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FreeCustom.Email (FCE) 临时邮箱服务实现 — REST API 方式。
 *
 * <p>纯 REST API，无需浏览器，无需 IMAP：</p>
 * <ul>
 *   <li>POST /v1/inboxes {"inbox": "前缀@域名"} → 注册地址</li>
 *   <li>GET  /v1/inboxes/{inbox}/messages → 收取邮件列表</li>
 * </ul>
 *
 * <p>注册地址：https://www.freecustom.email/auth</p>
 * <p>IMAP 备用：imap.freecustom.email:993（需 Growth 套餐）</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("fce")
public class FceEmailProvider implements EmailProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_BASE = "https://api2.freecustom.email/v1";
    private static final String[] DOMAINS = {"ditube.info", "ditapi.info"};

    /** OTP 验证码正则：3+3格式（如 ABC-123）或纯6位数字 */
    private static final Pattern OTP_PATTERN = Pattern.compile("\\b([A-Z0-9]{3})-?([A-Z0-9]{3})\\b");
    private static final Pattern NUM_OTP_PATTERN = Pattern.compile("\\b(\\d{6})\\b");

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
            log.warn("[FceEmail] FCE_API_KEY 未配置");
            return null;
        }
        String prefix = randomPrefix(10);
        String domain = DOMAINS[(int) (Math.random() * DOMAINS.length)];
        String email = prefix + "@" + domain;
        try {
            String body = HttpClientFactory.of(API_BASE + "/inboxes")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body("{\"inbox\":\"" + email + "\"}")
                    .post()
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
            return Collections.emptyList();
        }
        try {
            String url = API_BASE + "/inboxes/" + java.net.URLEncoder.encode(email, "UTF-8") + "/messages";
            String body = HttpClientFactory.of(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .get()
                    .getBodyString();
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return Collections.emptyList();
            }
            List<EmailInfo> result = new ArrayList<>();
            for (JsonNode msg : data) {
                result.add(EmailInfo.builder()
                        .id(msg.path("id").asText(null))
                        .from(msg.path("from").path("address").asText(
                                msg.path("from").asText(null)))
                        .subject(msg.path("subject").asText(null))
                        .body(msg.path("body").asText(
                                msg.path("text").asText(null)))
                        .html(msg.path("html").asText(null))
                        .createdAt(msg.path("created_at").asText(null))
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.warn("[FceEmail] 收取邮件失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public EmailInfo fetchFirstEmail(String email) {
        List<EmailInfo> emails = fetchEmails(email);
        return emails.isEmpty() ? null : emails.get(0);
    }

    /**
     * 从邮件内容中提取 OTP 验证码。
     */
    public static String extractOtp(String text) {
        if (text == null) return null;
        Matcher m = OTP_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1) + m.group(2);
        }
        Matcher nm = NUM_OTP_PATTERN.matcher(text);
        return nm.find() ? nm.group(1) : null;
    }

    private static String randomPrefix(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return sb.toString();
    }

    private static String resolveApiKey() {
        String key = System.getProperty("fce.api.key");
        if (key != null && !key.isBlank()) return key.trim();
        key = System.getenv("FCE_API_KEY");
        return key;
    }
}
