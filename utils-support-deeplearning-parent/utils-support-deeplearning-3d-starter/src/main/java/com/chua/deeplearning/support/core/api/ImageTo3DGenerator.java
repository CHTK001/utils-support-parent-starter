package com.chua.deeplearning.support.core.api;

import com.chua.deeplearning.support.core.model.Model3D;
import com.chua.deeplearning.support.core.model.Model3DStyle;
import com.chua.deeplearning.support.core.model.Model3DFormat;

import java.io.IOException;

/**
 * 图生 3D 生成器
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageTo3DGenerator {

    /**
     * 根据单张图片生成 3D 模型
     *
     * @param image        图片字节（PNG/JPG/WebP，最多 8MB）
     * @param format       输出格式
     * @param style        风格
     * @param quality      质量（draft / standard / high）
     * @return 3D 模型
     * @throws IOException IO 异常
     */
    Model3D generate(byte[] image, Model3DFormat format, Model3DStyle style, String quality) throws IOException;

    /**
     * 根据多视角图片生成 3D 模型
     *
     * @param images       多视角图片列表（最多 6 张）
     * @param format       输出格式
     * @param style        风格
     * @param quality      质量（draft / standard / high）
     * @return 3D 模型
     * @throws IOException IO 异常
     */
    Model3D generate(byte[][] images, Model3DFormat format, Model3DStyle style, String quality) throws IOException;
}