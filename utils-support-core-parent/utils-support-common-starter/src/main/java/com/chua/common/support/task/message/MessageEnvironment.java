package com.chua.common.support.task.message;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息环境配置
 *
 * <p>集中管理消息推送相关的环境配置，如 SMTP 服务器、API Key、模板目录等。
   * 不同的 消息提供者 实现读取各自需要的配置项。
 *
 * <h3>常见配置项</h3>
 * <pre>
 *   email:
 *     smtp.host       SMTP 服务器地址
 *     smtp.port       SMTP 端口（默认 587）
 *     smtp.username   SMTP 用户名
 *     smtp.password   SMTP 密码
 *     smtp.from       发件人地址
 *
 *   sms (tencent):
 *     sms.secretId   腾讯云 SecretId
 *     sms.secretKey  腾讯云 SecretKey
 *     sms.appId      短信应用 ID
 *     sms.signName    短信签名
 *
 *   sms (alibaba):
 *     sms.accessKey   阿里云 AccessKey
 *     sms.secretKey   阿里云 SecretKey
 *     sms.signName    短信签名
 *
 *   feishu:
 *     feishu.appId        飞书应用 ID
 *     feishu.appSecret    飞书应用密钥
 *     feishu.webhookUrl   飞书机器人 Webhook
 *
 *   dingding:
 *     dingding.webhookUrl   钉钉机器人 Webhook
 *     dingding.secret       钉钉加签密钥
 *
 *   desktop:
 *     desktop.icon         通知图标路径
 * </pre>
 *
 * @author CH
 * @since 2026/07/17
 */
public class MessageEnvironment {

    /** 属性 */
    private final Map<String, String> properties = new ConcurrentHashMap<>();

    /**
     * 设置配置项
     *
     * @param key   配置键
     * @param value 配置值
     * @return 当前实例
     */
    public MessageEnvironment set(String key, String value) {
        properties.put(key, value);
        return this;
    }

    /**
     * 获取配置值
     *
     * @param key 配置键
     * @return 配置值，不存在返回 空
     */
    public String get(String key) {
        return properties.get(key);
    }

    /**
     * 获取配置值（带默认值）
     *
     * @param key      配置键
     * @param defValue 默认值
     * @return 配置值或默认值
     */
    public String get(String key, String defValue) {
        return properties.getOrDefault(key, defValue);
    }

    /**
     * 获取所有配置
     *
     * @return 配置映射
     */
    public Map<String, String> getAll() {
        return properties;
    }
}
