package com.chua.common.support.objects.environment;

import com.chua.common.support.config.source.PropertySource;

/**
* 环境配置接口。
*
* <p>提供配置属性的读取和写入能力，是配置注入系统的核心接口。
* 环境配置聚合了多个 {@link ConfigSource}，按照优先级顺序进行配置查找。</p>
*
* <p>配置查找优先级（从高到低）：
* <ol>
*   <li>通过 {@link #setProperty} 手动设置的属性</li>
*   <li>各 ConfigSource 中的属性（按 ConfigSource 优先级排序）</li>
*   <li>默认值</li>
* </ol></p>
*
* @author CH
* @since 2024/12/20
 */
public interface Environment {

    /**
    * 获取配置属性（字符串类型）。
    *
    * <p>从当前环境配置中查找指定 key 对应的配置值，
    * 查找顺序遵循优先级规则（手动设置属性 &gt; 配置源 属性 &gt; 默认值）。
    * 如果所有来源均未找到该 键，则返回 空。</p>
    *
    * @param key 配置键，如 "服务端.端口"，不可为 空
    * @return 配置值，不存在时返回 空
     */
    String getProperty(String key);

    /**
    * 获取配置属性，带默认值。
    *
    * <p>当指定配置键不存在于任何配置源中时，返回传入的默认值。
    * 默认值不会被写入环境配置，仅作为本次查询的兜底值。</p>
    *
    * @param key          配置键，不可为 空
    * @param defaultValue 默认值，当配置键不存在时返回
    * @return 配置值，不存在时返回 默认值
     */
    String getProperty(String key, String defaultValue);

    /**
    * 获取配置属性，按类型转换。
    *
    * <p>从配置源中获取原始值后，通过 {@link com.chua.common.support.converter.Converter}
    * 自动转换为目标类型。支持 字符串、Integer、布尔值、Long 等常见类型。</p>
    *
    * @param key        配置键，不可为 空
    * @param targetType 目标类型，不可为 空
    * @param <T>        泛型类型
    * @return 转换后的配置值，不存在时返回 空
     */
    <T> T getProperty(String key, Class<T> targetType);

    /**
    * 获取配置属性，按类型转换，带默认值。
    *
    * <p>当配置键不存在或类型转换失败时，返回传入的默认值。
    * 类型转换依据 {@link com.chua.common.support.converter.Converter#convertIfNecessary} 进行。</p>
    *
    * @param key          配置键，不可为 空
    * @param targetType   目标类型，不可为 空
    * @param defaultValue 默认值，当配置键不存在或转换失败时返回
    * @param <T>          泛型类型
    * @return 转换后的配置值，不存在时返回 默认值
     */
    <T> T getProperty(String key, Class<T> targetType, T defaultValue);

    /**
    * 设置配置属性。
    *
    * <p>手动设置的属性具有最高优先级，会覆盖所有 ConfigSource 中的同名属性。
    * 设置后会触发 {@link EnvironmentChangeListener} 通知。</p>
    *
    * @param key   配置键，不可为 空
    * @param value 配置值
     */
    void setProperty(String key, Object value);

    /**
    * 是否包含指定配置键。
    *
    * <p>依次检查手动设置的属性和所有 ConfigSource 中的属性，
    * 只要任一来源包含该 键 即返回 true。</p>
    *
    * @param key 配置键
    * @return 是否包含该配置键
     */
    boolean containsProperty(String key);

    /**
    * 注册配置变更监听器。
    *
    * <p>当配置属性发生变化时，会通知所有已注册的监听器。
    * 监听器按注册顺序依次调用。同一个监听器重复注册会产生多次回调。</p>
    *
    * @param listener 配置变更监听器，不可为 空
     */
    void addChangeListener(EnvironmentChangeListener listener);

    /**
    * 移除配置变更监听器。
    *
    * <p>移除后该监听器不再接收配置变更通知。
    * 如果监听器未曾注册，则此方法无任何效果。</p>
    *
    * @param listener 配置变更监听器，不可为 空
     */
    void removeChangeListener(EnvironmentChangeListener listener);

    /**
    * 添加配置源。
    *
    * <p>配置源按优先级排序，优先级高的配置源中的属性会覆盖优先级低的。
    * 添加后立即生效，后续的配置查询会包含该配置源的数据。</p>
    *
    * @param propertySource 配置源，不可为 空
     */
    void addConfigSource(PropertySource propertySource);

    /**
    * 移除配置源。
    *
    * <p>移除后该配置源中的属性不再参与配置查询。
    * 如果配置源未曾添加，则此方法无任何效果。</p>
    *
    * @param propertySource 配置源，不可为 空
     */
    void removeConfigSource(PropertySource propertySource);


    /**
    * 刷新所有配置源。
    *
    * <p>依次调用每个 ConfigSource 的 {@link PropertySource#refresh()} 方法，
    * 刷新完成后触发所有配置变更监听器。
    * 刷新过程中单个配置源的异常不会中断其他配置源的刷新。</p>
     */
    void refresh();
}