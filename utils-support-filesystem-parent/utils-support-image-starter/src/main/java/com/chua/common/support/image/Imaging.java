package com.chua.common.support.image;

import java.awt.image.BufferedImage;
/**
 * @author CH
 * @since 4.0.0.42
 */

public interface Imaging {

    /**
     * image。
     *
     * @param image 方法入参 image
     * @return Imaging 对象
     */
    Imaging image(BufferedImage image);

    /**
     * image。
     *
     * @param image 方法入参 image
     * @return Imaging 对象
     */
    Imaging image(byte[] image);

    /**
     * image。
     *
     * @param file 文件，不允许为 null
     * @return Imaging 对象
     */
    Imaging image(java.io.File file);

    /**
     * 类型。
     *
     * @param type 类型，不允许为 null
     * @return Imaging 对象
     */
    Imaging type(String type);

    /**
     * outputQuality。
     *
     * @param outputQuality 方法入参 outputQuality
     * @return Imaging 对象
     */
    Imaging outputQuality(float outputQuality);

    /**
     * media类型。
     *
     * @return 结果值
     */
    com.chua.common.support.media.MediaType mediaType();

    /**
     * 获取BufferedImage。
     *
     * @return BufferedImage 对象
     */
    BufferedImage getBufferedImage();
}
