package com.chua.common.support.datasearch.email.spi.impl;

import com.chua.common.support.datasearch.email.model.EmailInfo;
import com.chua.common.support.datasearch.email.spi.EmailProvider;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Store;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FreeCustom.Email (FCE) 临时邮箱服务实现 — IMAP 方式收信。
 *
 * <p>创建邮箱通过 REST API，收信通过标准 IMAP（RFC 3501）：</p>
 * <ul>
 *   <li>IMAP: {@code imap.freecustom.email:993}，TLS 加密</li>
 *   <li>用户名：邮箱地址（如 {@code xxx@ditapi.info}）</li>
 *   <li>密码：FCE API Key（{@code fce_xxx}）</li>
 * </ul>
 *
 * <p>注册地址：https://www.freecustom.email/auth</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("fce")
public class FceEmailProvider implements EmailProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_BASE = "https://api2.freecustom.email/v1";
    private static final String IMAP_HOST = "imap.freecustom.email";
    private static final int IMAP_PORT = 993;
    private static final String[] DOMAINS = {"ditapi.info", "fce.email"};

    /** OTP 验证码正则：6位大写字母数字 */
    private static final Pattern OTP_PATTERN = Pattern.compile("\\b([A-Z0-9]{3})-?([A-Z0-9]{3})\\b");

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
            return Collections.emptyList();
        }
        try {
            Properties props = new Properties();
            props.put("mail.imap.ssl.enable", "true");
            props.put("mail.imap.port", String.valueOf(IMAP_PORT));
            props.put("mail.imap.ssl.trust", IMAP_HOST);
            props.put("mail.imap.auth.login.disable", "true");

            Store store = jakarta.mail.Session.getInstance(props)
                    .getStore("imaps");
            store.connect(IMAP_HOST, IMAP_PORT, email, apiKey);

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            List<EmailInfo> result = new ArrayList<>();
            Message[] messages = inbox.getMessages();
            for (Message msg : messages) {
                result.add(toEmailInfo(msg));
            }

            inbox.close(false);
            store.close();
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
     * 从邮件中提取 OTP 验证码（3+3格式，如 ABC-123）。
     */
    public static String extractOtp(String text) {
        if (text == null) return null;
        Matcher m = OTP_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1) + m.group(2);
        }
        // 纯6位数字
        Pattern num = Pattern.compile("\\b(\\d{6})\\b");
        Matcher nm = num.matcher(text);
        return nm.find() ? nm.group(1) : null;
    }

    private EmailInfo toEmailInfo(Message msg) throws Exception {
        String subject = msg.getSubject();
        String from = msg.getFrom()[0].toString();
        String createdAt = msg.getReceivedDate() != null
                ? msg.getReceivedDate().toString() : "";

        // 提取纯文本正文
        String body = "";
        Object content = msg.getContent();
        if (content instanceof String) {
            body = (String) content;
        } else if (content instanceof jakarta.mail.Multipart) {
            body = extractTextFromMultipart((jakarta.mail.Multipart) content);
        }

        return EmailInfo.builder()
                .subject(subject)
                .from(from)
                .body(body)
                .createdAt(createdAt)
                .build();
    }

    private String extractTextFromMultipart(jakarta.mail.Multipart mp) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = 0; i < mp.getCount(); i++) {
            jakarta.mail.BodyPart part = mp.getBodyPart(i);
            Object content = part.getContent();
            if (content instanceof String) {
                baos.write(((String) content).getBytes(StandardCharsets.UTF_8));
            } else if (part.isMimeType("text/*")) {
                byte[] bytes = new byte[part.getInputStream().available()];
                part.getInputStream().read(bytes);
                baos.write(bytes);
            }
        }
        return baos.toString(StandardCharsets.UTF_8);
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
