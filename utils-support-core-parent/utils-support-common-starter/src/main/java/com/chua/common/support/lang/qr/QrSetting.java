package com.chua.common.support.lang.qr;

import lombok.Data;

/**
 * 二维码配置类
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class QrSetting {
    /**
     * 二维码宽度
     */
    private int width;

    /**
     * 二维码高度
     */
    private int height;

    /**
     * 纠错等级
     */
    private CodeLevel codeLevel;

    /**
     * 数据点设置
     */
    private CodePointSetting codePointSetting;

    /**
     * 定位图案设置
     */
    private CodeEyeSetting codeEyeSetting;

    /**
     * 背景样式设置
     */
    private BackgroundSetting backgroundSetting;

    /**
     * Logo设置
     */
    private LogoSetting logoSetting;

    /**
     * 前景设置
     */
    private FrontSetting frontSetting;

    /**
     * 判断是否设置了背景样式
     *
     * @return 如果背景样式设置不为空，则返回true
     */
    public boolean hasBackgroundStyle() {
        if (backgroundSetting != null) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否设置了Logo
     *
     * @return 如果Logo设置不为空且Logo图片不为空，则返回true
     */
    public boolean hasLogo() {
        if (logoSetting != null && logoSetting.getLogoImage() != null) {
            return true;
        }
        return false;
    }
}
