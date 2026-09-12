package com.chua.common.support.task.message;

import java.util.List;

/**
 * 消息推送接口
 *
 * <p>统一的消息发送抽象，支持多种推送渠道（邮件/短信/即时消息/桌面通知等）。
   * 实现类通过 SPI 机制注册，调用方通过 提供者 标识选择具体实现。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   // 获取邮件推送
 *   MessagePush push = MessagePush.create("email");
 *
 *   // 发送消息
 *   MessageResponse resp = push.send(MessageRequest.builder()
 *       .to("user@example.com")
 *       .subject("通知")
 *       .content("您的订单已发货")
 *       .build());
 * }</pre>ubject("通知")
 *       .content("您的订单已发货")
 *       .build());
 * }</pre>
 *
 * @author CH
 * @since 2026/07/17
 */
public interface MessagePush {

    /**
     * 获取推送渠道标识
     *
     * @return 渠道名（如 "email", "sms", "feishu", "dingding", "desktop"）
     */
    String getProvider();

    /**
     * 发送消息
     *
     * @param request 消息请求
     * @return 消息响应
     * @throws Exception 发送失败时抛出
     */
    MessageResponse send(MessageRequest request) throws Exception;

    /**
     * 获取模板列表
     *
     * @return 模板列表
     */
    List<TemplateInfo> listTemplates();

    /**
      * 根据 标识 获取模板
     *
     * @param templateId 模板 标识
     * @return 模板信息，不存在返回 空
     */
    TemplateInfo getTemplate(String templateId);

    /**
     * 使用模板发送消息
     *
     * @param templateId 模板 标识
     * @param params     模板参数
     * @param request    消息请求（转为/主题 等）
     * @return 消息响应
     * @throws Exception 发送失败时抛出
     */
    default MessageResponse sendTemplate(String templateId, java.util.Map<String, String> params, MessageRequest request) throws Exception {
        TemplateInfo template = getTemplate(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在: " + templateId);
        }
        String content = template.content();
        for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
            content = content.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        request.setContent(content);
        return send(request);
    }

    /**
     * 使用模板发送消息（简版）
     *
     * <p>仅需模板 ID 和参数，接收人从模板配置或默认值获取。
     *
     * @param templateId 模板 标识
     * @param to         接收人
     * @param params     模板参数（键 → 值，替换模板中的 {{键}} 占位符）
     * @return 消息响应
     * @throws Exception 发送失败时抛出
     */
    default MessageResponse sendTemplate(String templateId, String to, java.util.Map<String, String> params) throws Exception {
        MessageRequest request = MessageRequest.builder()
                .to(to)
                .templateId(templateId)
                .templateParams(params)
                .build();
        return sendTemplate(templateId, params, request);
    }

    /**
      * 创建指定 提供者 的 消息push 实例
     *
     * @param provider 渠道标识
     * @return MessagePush 实例
     */
    static MessagePush create(String provider) {
        return com.chua.common.support.spi.ServiceProvider.of(MessagePush.class)
                .getNewExtension(provider);
    }
}
