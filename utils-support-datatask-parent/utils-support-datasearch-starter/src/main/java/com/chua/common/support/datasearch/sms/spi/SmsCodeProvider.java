package com.chua.common.support.datasearch.sms.spi;

import com.chua.common.support.datasearch.sms.model.SmsMessageInfo;
import com.chua.common.support.datasearch.sms.model.SmsPhoneInfo;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 接码（虚拟号码接收短信）数据提供者 SPI 接口。
 *
 * <p>封装在线接码平台的能力：获取当前可用的虚拟号码列表、随机抽取一个号码、
 * 以及读取号码收到的短信。典型实现为「接码号」（jiemahao.com）。</p>
 *
 * <p>实现（如 {@code JieMaHaoSmsCodeProvider}）可同时实现
 * {@link com.chua.common.support.lang.directory.PolledDirectory}，
 * 通过注册 {@link com.chua.common.support.lang.directory.PolledListener} 监听号码列表变化回调
 * （新增 / 变更 / 下架）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SmsCodeProvider {

    /**
     * 获取数据源名称。
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 获取当前可用的全部虚拟号码。
     *
     * @return 号码列表；获取失败返回空列表
     */
    List<SmsPhoneInfo> listPhones();

    /**
     * 随机获取一个可用虚拟号码。
     *
     * <p>实现基于 {@link #listPhones()} 随机抽取，无可用号码时返回空。</p>
     *
     * @return 随机号码；无可用号码返回空
     */
    default SmsPhoneInfo randomPhone() {
        List<SmsPhoneInfo> phones = listPhones();
        if (phones == null || phones.isEmpty()) {
            return null;
        }
        return phones.get(ThreadLocalRandom.current().nextInt(phones.size()));
    }

    /**
     * 查询指定虚拟号码收到的短信列表。
     *
     * <p>部分平台（如 jiemahao.com）的短信详情页受人机验证保护，
     * 实现应尽力而为，无法获取时返回空列表。</p>
     *
     * @param phone 虚拟号码
     * @return 短信列表；查询失败返回空列表
     */
    List<SmsMessageInfo> fetchMessages(SmsPhoneInfo phone);
}