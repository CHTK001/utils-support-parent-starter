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
* mail.tm 免费临时邮箱服务实现。
*
* <p>无需注册，无需 API Key：</p>
* <ul>
*   <li>POST /api/accounts — 创建邮箱账号</li>
*   <li>POST /api/token — 获取 JWT</li>
*   <li>GET  /api/messages — 收取邮件</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("mail-tm")
public class MailTmEmailProvider implements EmailProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器
    private static final String BASE = "https://api.mail.tm"; // 基础

    @Override
    public String name() {
        return "mail-tm";
    }

    @Override
    public String createEmail() {
        try {
            // 1. 获取可用域名
            String domainsJson = HttpClientFactory.of(BASE + "/domains")
                    .get().getBodyString();
            JsonNode domains = MAPPER.readTree(domainsJson);
            JsonNode members = domains.path("hydra:member");
            if (!members.isArray() || members.isEmpty()) {
                log.warn("[MailTM] 无可用域名");
                return null;
            }
            String domain = members.get(0).path("domain").asText(); // [P3C 四十一 豁免] JsonNode 数组下标访问（非 List/Collection）
            if (domain.isBlank()) {
                log.warn("[MailTM] 域名解析失败");
                return null;
            }

            // 2. 创建账号
            String prefix = randomPrefix(10);
            String email = prefix + "@" + domain;
            String password = "Gk" + randomPrefix(14) + "!1";

            String createBody = "{\"address\":\"" + email
                    + "\",\"password\":\"" + password + "\"}";
            String createResp = HttpClientFactory.of(BASE + "/accounts")
                    .header("Content-Type", "application/json")
                    .body(createBody)
                    .post()
                    .getBodyString();
            JsonNode created = MAPPER.readTree(createResp);
            if (!created.has("address")) {
                log.warn("[MailTM] 创建账号失败: {}", createResp);
                return null;
            }

 // 3. 获取 令牌
            String tokenBody = "{\"address\":\"" + email
                    + "\",\"password\":\"" + password + "\"}";
            String tokenResp = HttpClientFactory.of(BASE + "/token")
                    .header("Content-Type", "application/json")
                    .body(tokenBody)
                    .post()
                    .getBodyString();
            JsonNode tokenNode = MAPPER.readTree(tokenResp);
            String token = tokenNode.path("token").asText("");

            if (token.isBlank()) {
                log.warn("[MailTM] 获取 token 失败");
                return null;
            }

 // 4. 缓存 令牌
            System.setProperty("mailtm.token." + email, token);
            log.info("[MailTM] 创建邮箱成功: {}", email);
            return email;
        } catch (Exception e) {
            log.warn("[MailTM] 创建邮箱异常: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<EmailInfo> fetchEmails(String email) {
        String token = System.getProperty("mailtm.token." + email);
        if (token == null || token.isBlank()) {
            log.warn("[MailTM] 未找到 token: {}", email);
            return Collections.emptyList();
        }
        try {
            String resp = HttpClientFactory.of(BASE + "/messages")
                    .header("Authorization", "Bearer " + token)
                    .get()
                    .getBodyString();
            JsonNode root = MAPPER.readTree(resp);
            JsonNode members = root.path("hydra:member");
            if (!members.isArray()) {
                return Collections.emptyList();
            }
            List<EmailInfo> result = new ArrayList<>();
            for (JsonNode msg : members) {
                String id = msg.path("id").asText(null);
                String subject = msg.path("subject").asText(null);
                String from = msg.path("from").path("address").asText(null);
                String createdAt = msg.path("createdAt").asText(null);
                // 获取完整内容
                if (id != null) {
                    try {
                        String detailResp = HttpClientFactory.of(BASE + "/messages/" + id)
                                .header("Authorization", "Bearer " + token)
                                .get()
                                .getBodyString();
                        JsonNode detail = MAPPER.readTree(detailResp);
                        String body = detail.path("text").asText("")
                                + detail.path("html").asText("");
                        result.add(EmailInfo.builder()
                                .id(id)
                                .from(from)
                                .subject(subject)
                                .body(body)
                                .createdAt(createdAt)
                                .build());
                    } catch (Exception ignored) {
                        result.add(EmailInfo.builder()
                                .id(id)
                                .from(from)
                                .subject(subject)
                                .createdAt(createdAt)
                                .build());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[MailTM] 收取邮件失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public EmailInfo fetchFirstEmail(String email) {
        List<EmailInfo> emails = fetchEmails(email);
        return emails.isEmpty() ? null : emails.getFirst();
    }

    /**
    * 随机前缀。
    * @param length 长度
    * @return 随机前缀的结果
     */
    private static String randomPrefix(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return sb.toString();
    }
}
