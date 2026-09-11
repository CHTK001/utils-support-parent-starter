package com.chua.deeplearning.support.core.api;

import com.chua.deeplearning.support.core.model.Model3D;
import com.chua.deeplearning.support.core.model.Model3DStyle;
import com.chua.deeplearning.support.core.model.Model3DFormat;

import java.io.IOException;

/**
 * 文生 3D 生成器
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TextTo3DGenerator {

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
}