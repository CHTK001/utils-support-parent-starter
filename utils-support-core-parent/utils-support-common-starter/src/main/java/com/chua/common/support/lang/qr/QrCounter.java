package com.chua.common.support.lang.qr;

import java.awt.image.BufferedImage;

/**
 * 二维码计数器接口。
 * 用于定义对图像进行二维码扫描并统计结果的操作规范。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface QrCounter {

    /**
     * 统计图像中所有二维码的数量及详细信息。
     * <p>
     * 该方法接收一个 BufferedImage 对象，解析其中包含的所有二维码，
     * 并返回包含每个二维码识别结果的数组。
     *
     * @param bufferedImage 待处理的图像对象，不能为 null
     * @return 包含二维码识别结果的数组；如果未检测到任何二维码，则返回空数组
     * @throws IllegalArgumentException 当输入图像为 null 时抛出
     */
    QrResult[] countQr(BufferedImage bufferedImage);
}
