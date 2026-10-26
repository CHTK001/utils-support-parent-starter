package com.chua.common.support.datasearch.sms.model;

import lombok.Builder;
import lombok.Data;

/**
 * 虚拟号码收到的短信信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class SmsMessageInfo {

    /**
     * 接收短信的虚拟号码
     */
    private String phone;

    /**
     * 发送方标识（号码或业务名）
     */
    private String from;

    /**
     * 短信内容
     */
    private String content;

    /**
     * 接收时间文本
     */
    private String time;

    /**
     * 从内容中尽力提取的验证码，无法提取时为空
     */
    private String verifyCode;
}