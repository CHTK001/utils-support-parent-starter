package com.chua.common.support.datasearch.sms.model;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 虚拟号码及其收到的短信快照。
 *
 * <p>承载 {@link SmsPhoneInfo} 与已抓取的 {@link SmsMessageInfo} 列表，
 * 用于 {@link com.chua.common.support.lang.directory.PolledDirectory} 事件回调时
 * 将号码与分析出的短信一并送达监听器，便于比对监听与手动获取的数据是否一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class SmsPhoneMessage {

    /**
     * 虚拟号码
     */
    private SmsPhoneInfo phone;

    /**
     * 该号码收到的短信列表；未获取到时为空列表
     */
    @Builder.Default
    private List<SmsMessageInfo> messages = Collections.emptyList();
}