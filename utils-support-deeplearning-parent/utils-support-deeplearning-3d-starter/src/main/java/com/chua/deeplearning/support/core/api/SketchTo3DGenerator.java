package com.chua.deeplearning.support.core.api;

import com.chua.deeplearning.support.core.model.Model3D;
import com.chua.deeplearning.support.core.model.Model3DStyle;
import com.chua.deeplearning.support.core.model.Model3DFormat;

import java.io.IOException;

/**
 * 草图生 3D 生成器
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SketchTo3DGenerator {

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
    Model3D generate(byte[] sketch, String description, Model3DFormat format, Model3DStyle style, String quality) throws IOException;
}