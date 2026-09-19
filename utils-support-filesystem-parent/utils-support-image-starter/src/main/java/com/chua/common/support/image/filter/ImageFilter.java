package com.chua.common.support.image.filter;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
/**
 * @author CH
 * @since 4.0.0.42
 */

public interface ImageFilter {

    /**
     * converter。
     *
     * @param image 方法入参 image
     * @return BufferedImage 对象
     * @throws Exception 当执行过程不满足前置条件时
     */
    BufferedImage converter(BufferedImage image) throws Exception;

    /**
     * converter。
     *
     * @param image 方法入参 image
     * @return Output流 对象
     * @throws Exception 当执行过程不满足前置条件时
     */
    OutputStream converter(InputStream image) throws Exception;

    /**
     * 获取Image格式化。
     *
     * @param name 名称，不允许为 null
     * @return 结果字符串
     */
    String getImageFormat(String name);

    /**
     * 获取Image格式化。
     *
     * @return 结果字符串
     */
    String getImageFormat();
}
