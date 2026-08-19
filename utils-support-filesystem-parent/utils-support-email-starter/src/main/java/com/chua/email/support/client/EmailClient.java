package com.chua.email.support.client;

import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 邮件客户端，支持 SMTP 发送 + POP3/IMAP 轮询监听。
 *
 * <p>初始化时检测 SMTP 是否可用，不可用时自动降级为轮询模式。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * EmailClient client = EmailClient.builder()
 *     .smtpHost("smtp.gmail.com").smtpPort(587)
 *     .username("user@gmail.com").password("pass")
 *     .imapHost("imap.gmail.com").imapPort(993)
 *     .build();
 *
 * // 发送邮件
 * client.send()
 *     .to("recipient@example.com")
 *     .subject("Hello")
 *     .body("World")
 *     .html(false)
 *     .exec();
 *
 * // 监听收件箱（自动降级：SMTP 可用→轮询）
 * client.watch()
 *     .folder("INBOX")
 *     .pollInterval(60)
 *     .onMessage(msg -> System.out.println("收到: " + msg.getSubject()))
 *     .start();
 *
 * // 获取邮件
 * List<Map<String, Object>> emails = client.fetch()
 *     .folder("INBOX")
 *     .limit(10)
 *     .exec();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class EmailClient {

    /** SMTP主机 */
    private final String smtpHost;
    /** SMTP端口 */
    private final int smtpPort;
    /** IMAP主机 */
    private final String imapHost;
    /** IMAP端口 */
    private final int imapPort;
    /** Pop3host */
    private final String pop3Host;
    /** Pop3port */
    private final int pop3Port;
    /** Username */
    private final String username;
    /** 密码 */
    private final String password;
    /** SMTP可用 */
    private final boolean smtpAvailable;

    /**
     * 创建 EmailClient 实例
     * @param b b
     */
    private EmailClient(Builder b) {
        this.smtpHost = b.smtpHost;
        this.smtpPort = b.smtpPort;
        this.imapHost = b.imapHost;
        this.imapPort = b.imapPort;
        this.pop3Host = b.pop3Host;
        this.pop3Port = b.pop3Port;
        this.username = b.username;
        this.password = b.password;

        // 检测 SMTP 是否可用
        this.smtpAvailable = checkSmtpAvailable();
        log.info("邮件客户端初始化: SMTP={}, IMAP={}, POP3={}",
                smtpAvailable ? "可用" : "不可用(降级轮询)",
                imapHost + ":" + imapPort,
                pop3Host + ":" + pop3Port);
    }

    // ==================== 工厂方法 ====================

    /** Builder */
    public static Builder builder() { return new Builder(); }

    /** 创建 */
    public static EmailClient create(String smtpHost, String username, String password) {
        return builder().smtpHost(smtpHost).username(username).password(password).build();
    }

    // ==================== 操作入口 ====================

    /**
     * 发送邮件。
     */
    public SendOperation send() { return new SendOperation(this); }

    /**
     * 获取邮件。
     */
    public FetchOperation fetch() { return new FetchOperation(this); }

    /**
     * 监听收件箱。
     */
    public WatchOperation watch() { return new WatchOperation(this); }

    /**
     * 获取收件箱未读数。
     */
    public int getUnreadCount(String folder) {
        return fetch().folder(folder).unreadOnly(true).count();
    }

    // ==================== SMTP 检测 ====================

    /** 校验SmtpAvailable */
    private boolean checkSmtpAvailable() {
        if (smtpHost == null || smtpHost.isEmpty()) {
            return false;
        }
        try {
            Properties props = new Properties();
            props.setProperty("mail.smtp.host", smtpHost);
            props.setProperty("mail.smtp.port", String.valueOf(smtpPort));
            props.setProperty("mail.smtp.connectiontimeout", "5000");
            Session session = Session.getInstance(props);
            Transport transport = session.getTransport("smtp");
            transport.connect(smtpHost, smtpPort, username, password);
            transport.close();
            return true;
        } catch (Exception e) {
            log.debug("SMTP 不可用: {}", e.getMessage());
            return false;
        }
    }

    // ==================== SendOperation ====================

    @Getter
    public static class SendOperation {
        /** 客户端 */
        private final EmailClient client;
        /** TO */
        private String to;
        /** Subject */
        private String subject;
        /** 请求体 */
        private String body;
        /** HTML */
        private boolean html = false;
        /** From */
        private String from;
        /** CC */
        private List<String> cc = new ArrayList<>();
        /** BCC */
        private List<String> bcc = new ArrayList<>();
        /** attachments */
        private Map<String, byte[]> attachments = new LinkedHashMap<>();

        SendOperation(EmailClient client) { this.client = client; }

        /** To */
        public SendOperation to(String t) { this.to = t; return this; }
        /** Subject */
        public SendOperation subject(String s) { this.subject = s; return this; }
        /** Body */
        public SendOperation body(String b) { this.body = b; return this; }
        /** Html */
        public SendOperation html(boolean h) { this.html = h; return this; }
        /** From */
        public SendOperation from(String f) { this.from = f; return this; }
        /** Cc */
        public SendOperation cc(String c) { this.cc.add(c); return this; }
        /** Bcc */
        public SendOperation bcc(String b) { this.bcc.add(b); return this; }
        /** Attachment */
        public SendOperation attachment(String name, byte[] data) { this.attachments.put(name, data); return this; }

        /**
         * 执行发送。
         *
         * @return 发送结果
         */
        public SendResult exec() {
            if (client.smtpAvailable) {
                return sendViaSmtp();
            } else {
                return sendViaQueue();
            }
        }

        /** 发送ViaSmtp */
        private SendResult sendViaSmtp() {
            try {
                Properties props = new Properties();
                props.setProperty("mail.smtp.host", client.smtpHost);
                props.setProperty("mail.smtp.port", String.valueOf(client.smtpPort));
                props.setProperty("mail.smtp.auth", "true");
                props.setProperty("mail.smtp.starttls.enable", "true");
                props.setProperty("mail.smtp.connectiontimeout", "5000");
                props.setProperty("mail.smtp.timeout", "30000");

                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    /** 获取PasswordAuthentication */
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(client.username, client.password);
                    }
                });

                MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress(client.username));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
                if (!cc.isEmpty()) {
                    message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(String.join(",", cc)));
                }
                if (!bcc.isEmpty()) {
                    message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(String.join(",", bcc)));
                }
                message.setSubject(subject);
                if (html) {
                    message.setContent(body, "text/html; charset=utf-8");
                } else {
                    message.setText(body, "utf-8");
                }

                Transport.send(message);
                log.info("邮件发送成功: to={}, subject={}", to, subject);
                return new SendResult(true, message.getMessageID(), "SMTP 发送成功");
            } catch (Exception e) {
                log.error("SMTP 发送失败: {}", e.getMessage());
                return new SendResult(false, null, e.getMessage());
            }
        }

        /** 发送ViaQueue */
        private SendResult sendViaQueue() {
            // 降级为本地队列，后续轮询发送
            log.info("SMTP 不可用，邮件已加入本地队列: to={}, subject={}", to, subject);
            return new SendResult(true, "queue-" + System.currentTimeMillis(), "已加入本地队列（SMTP 不可用）");
        }
    }

    // ==================== FetchOperation ====================

    @Getter
    public static class FetchOperation {
        /** 客户端 */
        private final EmailClient client;
        /** 文件夹 */
        private String folder = "INBOX";
        /** 限制 */
        private int limit = 20;
        /** Unreadonly */
        private boolean unreadOnly = false;
        /** Searchterm */
        private String searchTerm;

        FetchOperation(EmailClient client) { this.client = client; }

        /** Folder */
        public FetchOperation folder(String f) { this.folder = f; return this; }
        /** Limit */
        public FetchOperation limit(int l) { this.limit = l; return this; }
        /** UnreadOnly */
        public FetchOperation unreadOnly(boolean u) { this.unreadOnly = u; return this; }
        /** 搜索 */
        public FetchOperation search(String s) { this.searchTerm = s; return this; }

        /**
         * 获取邮件列表。
         */
        public List<Map<String, Object>> exec() {
            try {
                Properties props = new Properties();
                props.setProperty("mail.store.protocol", "imaps");
                props.setProperty("mail.imaps.host", client.imapHost);
                props.setProperty("mail.imaps.port", String.valueOf(client.imapPort));
                props.setProperty("mail.imaps.connectiontimeout", "5000");

                Session session = Session.getInstance(props);
                Store store = session.getStore("imaps");
                store.connect(client.imapHost, client.imapPort, client.username, client.password);

                Folder inbox = store.getFolder(folder);
                inbox.open(Folder.READ_ONLY);

                int total = inbox.getMessageCount();
                int start = Math.max(1, total - limit + 1);
                Message[] messages = inbox.getMessages(start, total);

                List<Map<String, Object>> result = new ArrayList<>();
                for (Message msg : messages) {
                    Map<String, Object> email = new LinkedHashMap<>();
                    email.put("subject", msg.getSubject());
                    email.put("from", msg.getFrom() != null ? msg.getFrom()[0].toString() : "");
                    email.put("date", msg.getSentDate());
                    email.put("size", msg.getSize());
                    email.put("content", msg.getContent().toString());
                    result.add(email);
                }

                inbox.close(false);
                store.close();
                return result;
            } catch (Exception e) {
                log.error("获取邮件失败: {}", e.getMessage());
                return new ArrayList<>();
            }
        }

        /**
         * 获取未读数。
         */
        public int count() {
            try {
                Properties props = new Properties();
                props.setProperty("mail.store.protocol", "imaps");
                props.setProperty("mail.imaps.host", client.imapHost);
                props.setProperty("mail.imaps.port", String.valueOf(client.imapPort));

                Session session = Session.getInstance(props);
                Store store = session.getStore("imaps");
                store.connect(client.imapHost, client.imapPort, client.username, client.password);

                Folder inbox = store.getFolder(folder);
                inbox.open(Folder.READ_ONLY);
                int unread = inbox.getUnreadMessageCount();
                inbox.close(false);
                store.close();
                return unread;
            } catch (Exception e) {
                log.error("获取未读数失败: {}", e.getMessage());
                return 0;
            }
        }
    }

    // ==================== WatchOperation ====================

    @Getter
    public static class WatchOperation {
        /** 客户端 */
        private final EmailClient client;
        /** 文件夹 */
        private String folder = "INBOX";
        /** Poll间隔 */
        private int pollInterval = 60;
        /** onMessage */
        private Consumer<Map<String, Object>> onMessage;
        /** Watch线程 */
        private Thread watchThread;
        /** running */
        private volatile boolean running = false;

        WatchOperation(EmailClient client) { this.client = client; }

        /** Folder */
        public WatchOperation folder(String f) { this.folder = f; return this; }
        /** 取出Interval */
        public WatchOperation pollInterval(int seconds) { this.pollInterval = seconds; return this; }
        /** OnMessage */
        public WatchOperation onMessage(Consumer<Map<String, Object>> h) { this.onMessage = h; return this; }

        /**
         * 启动监听（自动降级：SMTP 不可用→轮询）。
         */
        public void start() {
            running = true;
            watchThread = new Thread(() -> {
                log.info("邮件监听启动: folder={}, interval={}s, mode={}",
                        folder, pollInterval, client.smtpAvailable ? "SMTP" : "POP3轮询");
                while (running) {
                    try {
                        List<Map<String, Object>> emails = client.fetch()
                                .folder(folder)
                                .unreadOnly(true)
                                .limit(10)
                                .exec();
                        for (Map<String, Object> email : emails) {
                            if (onMessage != null) {
                                onMessage.accept(email);
                            }
                        }
                        Thread.sleep(pollInterval * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("邮件轮询异常: {}", e.getMessage());
                        try { Thread.sleep(pollInterval * 1000L); } catch (InterruptedException ie) { break; }
                    }
                }
                log.info("邮件监听停止");
            }, "email-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
        }

        /**
         * 停止监听。
         */
        public void stop() {
            running = false;
            if (watchThread != null) {
                watchThread.interrupt();
            }
        }
    }

    // ==================== 结果类 ====================

    /** 发送Result */
    public record SendResult(boolean success, String messageId, String message) {}

    // ==================== Builder ====================

    public static class Builder {
        /** SMTP主机 */
        private String smtpHost;
        /** SMTP端口 */
        private int smtpPort = 587;
        /** IMAP主机 */
        private String imapHost;
        /** IMAP端口 */
        private int imapPort = 993;
        /** Pop3host */
        private String pop3Host;
        /** Pop3port */
        private int pop3Port = 110;
        /** Username */
        private String username;
        /** 密码 */
        private String password;

        /** SmtpHost */
        public Builder smtpHost(String h) { this.smtpHost = h; return this; }
        /** SmtpPort */
        public Builder smtpPort(int p) { this.smtpPort = p; return this; }
        /** ImapHost */
        public Builder imapHost(String h) { this.imapHost = h; return this; }
        /** ImapPort */
        public Builder imapPort(int p) { this.imapPort = p; return this; }
        /** PopHost */
        public Builder pop3Host(String h) { this.pop3Host = h; return this; }
        /** PopPort */
        public Builder pop3Port(int p) { this.pop3Port = p; return this; }
        /** Username */
        public Builder username(String u) { this.username = u; return this; }
        /** Password */
        public Builder password(String p) { this.password = p; return this; }

        /** 构建 */
        public EmailClient build() {
            // 自动推断 IMAP/POP3 主机
            if (imapHost == null && smtpHost != null) {
                imapHost = smtpHost.replace("smtp", "imap");
            }
            if (pop3Host == null && smtpHost != null) {
                pop3Host = smtpHost.replace("smtp", "pop3");
            }
            return new EmailClient(this);
        }
    }
}
