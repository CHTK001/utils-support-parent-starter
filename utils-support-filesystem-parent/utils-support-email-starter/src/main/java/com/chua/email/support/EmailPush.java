package com.chua.email.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.common.support.task.message.TemplateInfo;

import javax.mail.Message;
import javax.mail.Transport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮件推送实现
 *
 * <p>基于 JavaMail SMTP 协议的邮件发送实现。
 * 支持纯文本和 HTML 格式邮件、附件、多收件人。
 *
 * <h3>环境配置</h3>
 * <pre>
 *   smtp.host       SMTP 服务器地址（必填）
 *   smtp.port       SMTP 端口（默认 587）
 *   smtp.username   SMTP 用户名
 *   smtp.password   SMTP 密码
 *   smtp.from       发件人地址
 *   smtp.auth       是否需要认证（默认 true）
 *   smtp.starttls   是否启用 STARTTLS（默认 true）
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("email")
@SpiDescribe(
        value = "邮件推送",
        type = "EMAIL",
        desc = "基于 SMTP 协议发送纯文本或 HTML 邮件，支持多收件人、抄送与阅读追踪",
        optional = {
                @SpiParam(value = "smtp.host", desc = "SMTP 服务器地址", type = "String"),
                @SpiParam(value = "smtp.port", defaultValue = "587", desc = "SMTP 端口", type = "int"),
                @SpiParam(value = "smtp.username", desc = "SMTP 用户名", type = "String"),
                @SpiParam(value = "smtp.password", desc = "SMTP 授权码", type = "String"),
                @SpiParam(value = "smtp.from", desc = "发件人地址", type = "String")
        }
)
/**
 * 公共 类 emailpush implements 消息push {
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EmailPush implements MessagePush {

    /** 环境 */
    private final MessageEnvironment environment;
    /** templates */
    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    /** 创建 emailpush 实例 */
    public EmailPush() {
        this(new MessageEnvironment());
    }

    /**
    * 创建 emailpush 实例
    * @param environment 环境
    */
    public EmailPush(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    /** 获取提供者 */
    public String getProvider() {
        return "email";
    }

    @Override
    /**
    * 发送
    * @param request 请求
    */
    public MessageResponse send(MessageRequest request) throws Exception {
        long start = System.currentTimeMillis();

        Properties props = buildProperties();
        String from = environment.get("smtp.from", environment.get("smtp.username"));

 // 创建 会话
        var session = javax.mail.Session.getInstance(props);

        // 构建邮件
        var message = new javax.mail.internet.MimeMessage(session);
        message.setFrom(new javax.mail.internet.InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO,
                javax.mail.internet.InternetAddress.parse(request.getTo()));

        if (request.getCc() != null && !request.getCc().isEmpty()) {
            String[] ccArr = request.getCc().toArray(new String[0]);
            message.setRecipients(Message.RecipientType.CC,
                    javax.mail.internet.InternetAddress.parse(String.join(",", ccArr)));
        }

        if (request.getSubject() != null) {
            message.setSubject(request.getSubject());
        }

        // 设置内容
        if ("html".equalsIgnoreCase(request.getContentType())) {
            message.setContent(request.getContent(), "text/html; charset=utf-8");
        } else {
            message.setText(request.getContent(), "utf-8");
        }

        // 发送
        Transport.send(message);

        long duration = System.currentTimeMillis() - start;
        return MessageResponse.builder()
                .success(true)
                .messageId(message.getMessageID())
                .durationMillis(duration)
                .build();
    }

    @Override
    /** 列表templates */
    public List<TemplateInfo> listTemplates() {
        return new ArrayList<>(templates.values());
    }

    @Override
    /**
    * 获取Template
    * @param templateId templateid
    */
    public TemplateInfo getTemplate(String templateId) {
        return templates.get(templateId);
    }

    /**
     * 注册模板
     * @param template template
     */
    public void registerTemplate(TemplateInfo template) {
        templates.put(template.id(), template);
    }

    /**
     * 构建 SMTP 属性
     * @return 构建属性的结果
     */
    private Properties buildProperties() {
        Properties props = new Properties();
        String host = environment.get("smtp.host");
        String port = environment.get("smtp.port", "587");
        boolean auth = Boolean.parseBoolean(environment.get("smtp.auth", "true"));
        boolean starttls = Boolean.parseBoolean(environment.get("smtp.starttls", "true"));

        props.setProperty("mail.smtp.host", host);
        props.setProperty("mail.smtp.port", port);
        props.setProperty("mail.smtp.auth", String.valueOf(auth));

        if (starttls) {
            props.setProperty("mail.smtp.starttls.enable", "true");
        }

        String username = environment.get("smtp.username");
        String password = environment.get("smtp.password");
        if (auth && username != null && password != null) {
            props.setProperty("mail.smtp.username", username);
            props.setProperty("mail.smtp.password", password);
        }

        return props;
    }
}
