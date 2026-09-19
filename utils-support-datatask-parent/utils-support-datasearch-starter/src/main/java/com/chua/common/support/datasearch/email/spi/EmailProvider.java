package com.chua.common.support.datasearch.email.spi;

import com.chua.common.support.datasearch.email.model.EmailInfo;

import java.util.List;

/**
 * 临时邮箱服务 SPI 接口。
 *
 * <p>提供临时邮箱的创建与邮件收取能力，用于自动注册场景（如 Grok 账号注册验证码接收）。</p>
 *
 * <p>典型实现：FreeCustom.Email (FCE)、mail.tm、LuckMail 等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface EmailProvider {

    /**
     * 获取数据源名称。
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 创建一个临时邮箱地址。
     *
     * @return 邮箱地址；创建失败返回 空
     */
    String createEmail();

    /**
     * 查询该邮箱收到的最新邮件列表。
     *
     * @param email 邮箱地址
     * @return 邮件列表（按时间倒序）；查询失败返回空列表
     */
    List<EmailInfo> fetchEmails(String email);

    /**
     * 查询该邮箱收到的第一封未读邮件。
     *
     * @param email 邮箱地址
     * @return 第一封邮件；无邮件时返回 空
     */
    default EmailInfo fetchFirstEmail(String email) {
        List<EmailInfo> emails = fetchEmails(email);
        return emails != null && !emails.isEmpty() ? emails.getFirst() : null;
    }
}
