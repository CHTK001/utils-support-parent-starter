package com.chua.common.support.datasearch.sms.model;

import lombok.Builder;
import lombok.Data;

/**
 * 接码平台的虚拟号码信息。
 *
 * <p>描述一个可接收短信的临时虚拟手机号码，包含号码展示、归属国家、
 * 最近活跃时间与短信查看地址。典型数据源为接码号（jiemahao.com）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class SmsPhoneInfo {

    /**
     * 号码在平台的槽位标识（如 {@code sms/?phone=98} 中的 98），平台内唯一
     */
    private String id;

    /**
     * 虚拟号码展示文本（如 {@code +1 6572911188}）
     */
    private String phone;

    /**
     * 国家/地区二字码（如 {@code US}），由国旗资源名推导，可能为空
     */
    private String countryCode;

    /**
     * 国家/地区名称（如 美国）
     */
    private String countryName;

    /**
     * 国旗图片地址
     */
    private String flagUrl;

    /**
     * 最近活跃时间文本（如 {@code 2 小时前}，平台提供的相对时间）
     */
    private String updateTime;

    /**
     * 平台备注（如 {@code 美国98号电话号码}）
     */
    private String remark;

    /**
     * 查看该号码收到的短信的地址
     */
    private String smsUrl;
}