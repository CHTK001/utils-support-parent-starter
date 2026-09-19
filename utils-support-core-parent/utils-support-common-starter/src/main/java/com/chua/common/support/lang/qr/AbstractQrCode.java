package com.chua.common.support.lang.qr;

import java.io.OutputStream;

/**
 * 二维码生成抽象基类。
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractQrCode {

    /**
     * 二维码配置信息。
     */
    protected final QrSetting setting;

    /**
     * 构造函数，初始化二维码配置。
     *
     * @param setting 二维码配置对象
     */
    public AbstractQrCode(QrSetting setting) {
        if (setting == null) {
            throw new IllegalArgumentException("QrSetting cannot be null");
        }
        this.setting = setting;
    }

    /**
     * 将内容输出到指定的输出流中。
     *
     * @param content           要生成的二维码内容
     * @param outputStream      输出流
     */
    public abstract void out(String content, OutputStream outputStream);
}
