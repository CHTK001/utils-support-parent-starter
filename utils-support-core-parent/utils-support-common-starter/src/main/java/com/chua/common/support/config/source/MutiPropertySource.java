package com.chua.common.support.config.source;

/**
 * 多属性源接口定义。
 * <p>
 * 该接口继承自 PropertySource，用于表示可以包含多个属性来源的属性配置源。
 * 实现类需要支持从不同的配置位置（如配置文件、环境变量、系统属性等）合并和提供属性值。
 * </p>
 *
 * @author CH
 */
public interface MutiPropertySource extends PropertySource {
}
