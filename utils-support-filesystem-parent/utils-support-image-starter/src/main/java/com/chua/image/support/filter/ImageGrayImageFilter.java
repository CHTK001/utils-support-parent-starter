package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 灰度化图像滤镜
*
* 将彩色图像转换为灰度图像的滤镜。通过将图像的颜色模式转换为灰度模式，
* 移除图像中的颜色信息，只保留亮度信息。
*
* 技术原理：
* - 使用缓冲镜像的类型_BYTE_GRAY模式
* - 自动应用标准的RGB到灰度转换公式
* - 保持图像的原始尺寸和细节
*
* 应用场景：
* - 图像预处理：为后续的图像分析做准备
* - 艺术效果：创建黑白照片效果
* - 文档处理：将彩色文档转换为灰度以节省存储空间
* - 打印优化：为黑白打印机准备图像
*
* 性能特点：
* - 高效转换：利用Java内置的颜色空间转换
* - 内存优化：灰度图像占用更少的内存空间
* - 质量保证：保持图像的细节和对比度
*
* @author CH
* @版本 1.0.0
* @since 4.0.0.42
 */
@Slf4j
@SpiDescribe("灰度化图像滤镜")
@Spi("gray")
public class ImageGrayImageFilter extends AbstractImageFilter {

    /**
    * 执行灰度化滤镜处理
    *
    * 将输入的彩色图像转换为灰度图像。使用缓冲镜像的类型_BYTE_GRAY
    * 模式来自动处理RGB到灰度的转换，确保转换质量和性能。
    *
    * @param src 源彩色图像
    * @param dst 目标图像（此参数未使用，方法会创建新的灰度图像）
    * @return 转换后的灰度图像，转换失败时返回null
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        try {
            // 创建一个灰度模式的图像，自动处理颜色空间转换
            BufferedImage grayImage = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            int width = src.getWidth();
            int height = src.getHeight();

 // 逐像素复制，缓冲镜像会自动进行RGB到灰度的转换
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    grayImage.setRGB(x, y, src.getRGB(x, y));
                }
            }

            return grayImage;
        } catch (Exception e) {
            log.error("灰度化滤镜处理失败", e);
            return null;
        }
    }
}
