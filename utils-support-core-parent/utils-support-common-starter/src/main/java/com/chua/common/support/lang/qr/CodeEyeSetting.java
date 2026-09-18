package com.chua.common.support.lang.qr;

import lombok.Data;

/**
* 二维码定位点配置类。
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class CodeEyeSetting {
    /**
    * 定位点样式配置。
    */
    private CodeEyeStyle codeEye;

    /**
    * 定位点颜色（支持十六进制或名称）。
    */
    private String codeEyeColor;

    /**
    * 通用定位点图片路径。
    */
    private String codeEyeImage;

    /**
    * 左上角定位点图片路径。
    */
    private String ltCodeEyeImage;

    /**
    * 左下角定位点图片路径。
    */
    private String lbCodeEyeImage;

    /**
    * 右上角定位点图片路径。
    */
    private String rtCodeEyeImage;

    /**
     * 获取LTCodeEyeImage
     * @return 结果字符串
     */
    public String getLTCodeEyeImage() {
        return ltCodeEyeImage;
    }

    /**
     * 获取LBCodeEyeImage
     * @return 结果字符串
     */
    public String getLBCodeEyeImage() {
        return lbCodeEyeImage;
    }

    /**
     * 获取RTCodeEyeImage
     * @return 结果字符串
     */
    public String getRTCodeEyeImage() {
        return rtCodeEyeImage;
    }
}
