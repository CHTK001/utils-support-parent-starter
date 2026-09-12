package com.chua.email.support.client;

import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
* 邮件轮询目录，实现 polled目录 接口。
*
* <p>通过 POP3/IMAP 协议轮询收件箱，检测新邮件。
* 支持 SMTP 不可用时自动降级为轮询模式。</p>
*
* <h2>使用方式</h2>
* <pre>{@code
* EmailDirectory dir = EmailDirectory.builder()
*     .imapHost("imap.gmail.com").imapPort(993)
*     .username("user@gmail.com").password("pass")
*     .folder("INBOX")
*     .pollInterval(60)
*     .build();
*
* dir.addListener(event -> {
*     System.out.println("新邮件: " + event.get("subject"));
* });
*
* dir.start(DirectoryPollerEnvironment.defaults());
* }</pre>邮件: " + event.get("subject"));
* });
*
* dir.启动(目录poller环境.默认());
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Getter
public class EmailDirectory implements PolledDirectory {

    /** IMAP主机 */
    private final String imapHost;
    /** IMAP端口 */
    private final int imapPort;
    /** 用户名 */
    private final String username;
    /** 密码 */
    private final String password;
    /** 文件夹 */
    private final String folder;
    /** Poll间隔秒 */
    private final int pollIntervalSeconds;

    /** 监听器 */
    private final List<PolledListener> listeners = new CopyOnWriteArrayList<>();
    /** Seen消息标识 */
    private final Set<String> seenMessageIds = ConcurrentHashMap.newKeySet();
    /** running */
    private volatile boolean running = false;

    /**
    * 创建 email目录 实例
    * @param b b
     */
    private EmailDirectory(Builder b) {
        this.imapHost = b.imapHost;
        this.imapPort = b.imapPort;
        this.username = b.username;
        this.password = b.password;
        this.folder = b.folder;
        this.pollIntervalSeconds = b.pollIntervalSeconds;
    }

    // ==================== 工厂方法 ====================

    /**
    * 构建器
    *
    * @return 构建器的结果
     */
    public static Builder builder() { return new Builder(); }

    // ==================== PolledDirectory 实现 ====================

    @Override
    /** 是否delegatedoperating系统 */
    public boolean isDelegatedOperatingSystem() {
        // 邮件需要轮询
        return false;
    }

    @Override
    /** 开始 */
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        running = true;
        log.info("邮件轮询启动: host={}:{}, folder={}, interval={}s",
                imapHost, imapPort, folder, pollIntervalSeconds);

        if (executor != null) {
            executor.start();
        } else {
            // 自动创建虚拟线程执行器
            startPollingThread();
        }
    }

    @Override
    /** Upgrade */
    public void upgrade() {
        if (!running) {
            return;
        }
        try {
            Properties props = new Properties();
            props.setProperty("mail.store.protocol", "imaps");
            props.setProperty("mail.imaps.host", imapHost);
            props.setProperty("mail.imaps.port", String.valueOf(imapPort));
            props.setProperty("mail.imaps.connectiontimeout", "5000");
            props.setProperty("mail.imaps.ssl.enable", "true");

            Session session = Session.getInstance(props);
            Store store = session.getStore("imaps");
            store.connect(imapHost, imapPort, username, password);

            Folder inbox = store.getFolder(folder);
            inbox.open(Folder.READ_ONLY);

            // 获取最新邮件
            int total = inbox.getMessageCount();
            int start = Math.max(1, total - 10);
            Message[] messages = inbox.getMessages(start, total);

            for (Message msg : messages) {
                String messageId = msg instanceof MimeMessage mime ? mime.getMessageID() : null;
                if (messageId != null && !seenMessageIds.contains(messageId)) {
                    seenMessageIds.add(messageId);

                    Map<String, Object> emailData = new LinkedHashMap<>();
                    emailData.put("messageId", messageId);
                    emailData.put("subject", msg.getSubject());
                    emailData.put("from", msg.getFrom() != null ? msg.getFrom()[0].toString() : "");
                    emailData.put("date", msg.getSentDate());
                    emailData.put("content", msg.getContent().toString());

                    EventObserver observer = EventObserver.builder()
                            .currentPath(folder)
                            .triggerFile(messageId)
                            .source(emailData)
                            .eventType(WatcherEvent.CREATE)
                            .build();
                    for (PolledListener listener : listeners) {
                        try {
                            listener.onCreate(WatcherEvent.CREATE, observer);
                        } catch (Exception e) {
                            log.error("邮件事件处理异常: {}", e.getMessage());
                        }
                    }
                }
            }

            inbox.close(false);
            store.close();
        } catch (Exception e) {
            log.error("邮件轮询异常: {}", e.getMessage());
        }
    }

    @Override
    /** 添加监听器 */
    public void addListener(PolledListener listener) {
        listeners.add(listener);
    }

    /**
    * 启动轮询线程。
     */
    private void startPollingThread() {
        Thread thread = new Thread(() -> {
            while (running) {
                try {
                    upgrade();
                    Thread.sleep(pollIntervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("邮件轮询异常: {}", e.getMessage());
                    try { Thread.sleep(pollIntervalSeconds * 1000L); } catch (InterruptedException ie) { break; }
                }
            }
        }, "email-poller");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    /** 关闭 */
    public void close() {
        running = false;
    }

    // ==================== Builder ====================
    /**
    * 构建器类。
    *
    * @author CH
    * @since 4.0.0
     */

    public static class Builder {
        /** IMAP主机 */
        private String imapHost;
        /** IMAP端口 */
        private int imapPort = 993;
        /** 用户名 */
        private String username;
        /** 密码 */
        private String password;
        /** 文件夹 */
        private String folder = "INBOX";
        /** Poll间隔秒 */
        private int pollIntervalSeconds = 60;

        /**
        * imap主机
        *
        * @param h h
        * @return imap主机的结果
         */
        public Builder imapHost(String h) { this.imapHost = h; return this; }
        /**
        * imap端口
        *
        * @param p p
        * @return imap端口的结果
         */
        public Builder imapPort(int p) { this.imapPort = p; return this; }
        /**
        * 用户名
        *
        * @param u u
        * @return 用户名的结果
         */
        public Builder username(String u) { this.username = u; return this; }
        /**
        * 密码
        *
        * @param p p
        * @return 密码的结果
         */
        public Builder password(String p) { this.password = p; return this; }
        /**
        * 文件夹
        *
        * @param f f
        * @return 文件夹的结果
         */
        public Builder folder(String f) { this.folder = f; return this; }
        /**
        * 取出间隔
        *
        * @param seconds seconds
        * @return poll间隔的结果
         */
        public Builder pollInterval(int seconds) { this.pollIntervalSeconds = seconds; return this; }

        /**
        * 构建
        *
        * @return 构建的结果
         */
        public EmailDirectory build() {
            if (imapHost == null) {
                throw new IllegalArgumentException("imapHost 不能为空");
            }
            if (username == null) {
                throw new IllegalArgumentException("username 不能为空");
            }
            return new EmailDirectory(this);
        }
    }
}
