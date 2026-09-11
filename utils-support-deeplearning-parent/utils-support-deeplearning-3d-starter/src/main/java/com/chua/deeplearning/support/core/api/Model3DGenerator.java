package com.chua.deeplearning.support.core.api;

import com.chua.deeplearning.support.core.model.Model3D;
import com.chua.deeplearning.support.core.model.Model3DStyle;
import com.chua.deeplearning.support.core.model.Model3DFormat;

import java.io.IOException;

/**
 * 3D 模型生成器
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Model3DGenerator {

    /**
     * 根据文本生成 3D 模型
     *
     * @param prompt       文本描述（英文效果最佳）
     * @param format       输出格式
     * @param style        风格
     * @param quality      质量（draft / standard / high）
     * @return 3D 模型
     * @throws IOException IO 异常
     */
    Model3D generate(String prompt, Model3DFormat format, Model3DStyle style, String quality) throws IOException;

    /**
     * 根据图片生成 3D 模型
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

    /**
     * 根据草图生成 3D 模型（无纹理几何体）
     *
     * @param sketch       草图字节（PNG/JPG/WebP）
     * @param description  草图描述（必填）
     * @param format       输出格式
     * @param style        风格
     * @param quality      质量（draft / standard / high）
     * @return 3D 模型
     * @throws IOException IO 异常
     */
    Model3D generateFromSketch(byte[] sketch, String description, Model3DFormat format, Model3DStyle style, String quality) throws IOException;

    /**
     * 风格化处理
     *
     * @param model        原始 3D 模型
     * @param style        目标风格
     * @param resolution   分辨率
     * @return 风格化后的 3D 模型
     * @throws IOException IO 异常
     */
    Model3D stylize(Model3D model, Model3DStyle style, int resolution) throws IOException;
}