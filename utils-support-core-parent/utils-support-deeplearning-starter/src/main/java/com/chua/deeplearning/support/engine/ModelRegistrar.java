package com.chua.deeplearning.support.engine;

/**
* 模型注册器 SPI 接口，用于子模块向 {@link ModelRegistry} 批量注册模型。
*
* @author CH
* @since 4.0.0.42
 */
@FunctionalInterface
public interface ModelRegistrar {

    void register(ModelRegistry registry);
}
