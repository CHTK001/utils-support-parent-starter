package com.chua.common.support.datasearch.email.spi.impl;

import com.chua.common.support.datasearch.email.model.EmailInfo;
import com.chua.common.support.datasearch.email.spi.EmailProvider;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * LuckMail 临时邮箱服务实现。
 *
 * <p>该实现通过 LuckMail HTTP API 购买邮箱并查询邮件。接口地址和认证信息支持环境变量及
 * JVM 系统属性覆盖；未配置认证信息或服务端返回错误时直接抛出异常，不伪造成功结果。</p>
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
    private final String messagesPath;

    /**
     * 创建 LuckMail 邮箱服务并读取配置。
     */
    public LuckMailEmailProvider() {
        this.apiKey = resolveConfig("LUCKMAIL_API_KEY", "");
        this.apiSecret = resolveConfig("LUCKMAIL_API_SECRET", "");
        this.baseUrl = resolveConfig("LUCKMAIL_BASE_URL", DEFAULT_BASE);
        this.projectCode = resolveConfig("LUCKMAIL_PROJECT_CODE", DEFAULT_PROJECT_CODE);
        this.emailType = resolveConfig("LUCKMAIL_EMAIL_TYPE", DEFAULT_EMAIL_TYPE);
        this.domain = resolveConfig("LUCKMAIL_DOMAIN", DEFAULT_DOMAIN);
        this.messagesPath = resolveConfig("LUCKMAIL_MESSAGES_PATH", "");
    }

    /**
     * 获取服务名称。
     *
     * @return 服务名称
     */
    @Override
    public String name() {
        return "luckmail";
    }

    /**
     * 通过 LuckMail API 购买一个临时邮箱。
     *
     * @return 服务端返回的邮箱地址
     * @throws IllegalStateException 配置缺失、响应不合法或服务端调用失败时抛出
     */
    @Override
    public String createEmail() {
        requireCredentials();
        String body = "{\"project_code\":\"" + escapeJson(projectCode)
                + "\",\"quantity\":1,\"email_type\":\"" + escapeJson(emailType)
                + "\",\"domain\":\"" + escapeJson(domain) + "\"}";
        try {
            ClientResponse response = HttpClientFactory.of(baseUrl + "/api/v1/purchase")
                    .header("X-API-Key", apiKey)
                    .header("X-API-Secret", apiSecret)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .post();
            String responseBody = requireSuccess(response, "购买临时邮箱");
            String email = findEmail(MAPPER.readTree(responseBody));
            if (email == null) {
                throw new IllegalStateException("LuckMail 购买响应中缺少邮箱地址");
            }
            log.info("[LuckMail] 创建邮箱成功: {}", email);
            return email;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LuckMail 创建临时邮箱失败", e);
        }
    }

    /**
     * 查询指定邮箱的邮件列表。
     *
     * @param email 邮箱地址
     * @return 服务端返回的邮件列表；服务端明确返回空集合时返回空列表
     * @throws IllegalStateException 配置缺失、响应不合法或服务端调用失败时抛出
     */
    @Override
    public List<EmailInfo> fetchEmails(String email) {
        requireCredentials();
        if (messagesPath.isBlank()) {
            throw new IllegalStateException("LuckMail 配置缺失: 请配置 LUCKMAIL_MESSAGES_PATH");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("查询 LuckMail 邮件时邮箱地址不能为空");
        }
        String url = baseUrl + normalizePath(messagesPath) + "?email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8);
        try {
            ClientResponse response = HttpClientFactory.of(url)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Secret", apiSecret)
                    .get();
            String responseBody = requireSuccess(response, "查询临时邮箱邮件");
            JsonNode messages = findArray(MAPPER.readTree(responseBody));
            if (messages == null) {
                throw new IllegalStateException("LuckMail 邮件响应中缺少邮件数组");
            }
            List<EmailInfo> result = new ArrayList<>();
            for (JsonNode message : messages) {
                result.add(toEmailInfo(message));
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LuckMail 查询临时邮箱邮件失败", e);
        }
    }

    /**
     * 查询指定邮箱收到的第一封邮件。
     *
     * @param email 邮箱地址
     * @return 第一封邮件；邮箱确实没有邮件时返回 {@code null}
     * @throws IllegalStateException 配置缺失或服务端调用失败时抛出
     */
    @Override
    public EmailInfo fetchFirstEmail(String email) {
        List<EmailInfo> emails = fetchEmails(email);
        return emails.isEmpty() ? null : emails.getFirst();
    }

    /**
     * 校验认证配置。
     */
    private void requireCredentials() {
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            throw new IllegalStateException("LuckMail 配置缺失: 请配置 LUCKMAIL_API_KEY 和 LUCKMAIL_API_SECRET");
        }
    }

    /**
     * 将 HTTP 响应转换为成功响应体。
     *
     * @param response HTTP 响应
     * @param operation 操作名称
     * @return 响应体
     */
    private static String requireSuccess(ClientResponse response, String operation) {
        if (!response.isSuccess()) {
            throw new IllegalStateException("LuckMail " + operation + "失败: HTTP "
                    + response.getStatusCode() + ", body=" + response.getBodyString());
        }
        return response.getBodyString();
    }

    /**
     * 从购买响应中提取邮箱地址。
     *
     * @param node 响应节点
     * @return 邮箱地址；未找到时返回 {@code null}
     */
    private static String findEmail(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            return value.contains("@") ? value : null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String email = findEmail(child);
                if (email != null) {
                    return email;
                }
            }
            return null;
        }
        for (String field : List.of("email", "address", "mail", "account")) {
            String email = findEmail(node.get(field));
            if (email != null) {
                return email;
            }
        }
        for (String field : List.of("data", "result", "payload")) {
            String email = findEmail(node.get(field));
            if (email != null) {
                return email;
            }
        }
        return null;
    }

    /**
     * 查找邮件数组。
     *
     * @param node 响应节点
     * @return 邮件数组；未找到时返回 {@code null}
     */
    private static JsonNode findArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        for (String field : List.of("messages", "items", "results", "data", "result")) {
            JsonNode child = node.get(field);
            JsonNode array = findArray(child);
            if (array != null) {
                return array;
            }
        }
        return null;
    }

    /**
     * 将服务端邮件节点转换为统一模型。
     *
     * @param node 邮件节点
     * @return 邮件信息
     */
    private static EmailInfo toEmailInfo(JsonNode node) {
        JsonNode fromNode = node.path("from");
        String from = fromNode.isObject() ? fromNode.path("address").asText(null) : fromNode.asText(null);
        return EmailInfo.builder()
                .id(node.path("id").asText(null))
                .from(from)
                .subject(node.path("subject").asText(null))
                .body(node.path("body").asText(node.path("text").asText(null)))
                .html(node.path("html").asText(null))
                .createdAt(node.path("createdAt").asText(node.path("created_at").asText(null)))
                .build();
    }

    /**
     * 规范化接口路径。
     *
     * @param path 配置路径
     * @return 以斜杠开头的接口路径
     */
    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * 转义 JSON 字符串内容。
     *
     * @param value 原始内容
     * @return 转义后的内容
     */
    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 读取环境变量或 JVM 系统属性配置。
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    private static String resolveConfig(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
