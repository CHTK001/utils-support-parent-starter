package com.chua.deeplearning.support.config;

import lombok.Builder;
import lombok.Getter;

/**
* 模型配置，包含模型路径和运行设备等参数。
*
* @author CH
* @since 4.0.0.42
 */
@Getter
@Builder
public class ModelSetting {

    /**
    * 模型文件路径
     */
    private String modelPath;

    /**
    * 运行设备（如 "cpu"、"gpu"）
     */
    private String device;
}
