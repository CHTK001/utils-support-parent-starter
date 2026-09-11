package com.chua.deeplearning.support.core.api;

import com.chua.deeplearning.support.core.model.Model3D;
import com.chua.deeplearning.support.core.model.Model3DStyle;

import java.io.IOException;

/**
 * 3D 模型风格化器
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Model3DStylizer {

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