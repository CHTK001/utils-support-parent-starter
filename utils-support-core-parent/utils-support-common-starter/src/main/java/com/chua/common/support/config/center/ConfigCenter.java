package com.chua.common.support.config.center;

import java.util.Map;


/**
* 配置中心接口。
*
* <p>抽象配置中心的功能，支持配置的获取、发布、删除和监听。
* 类似于 Nacos、Apollo、Consul 等配置中心的通用抽象。
* 配置按 dataId 和 group 进行隔离，每个 dataId 下包含多个配置项（key-value）。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface ConfigCenter extends AutoCloseable {

    /**
    * 获取指定 dataId 的完整配置。
    *
    * @param dataId 配置的 dataId，如 "application.yml"
    * @return 配置项映射表，不存在时返回空 Map
    */
    Map<String, Object> get(String dataId);

    /**
    * 获取指定 dataId 和 group 的完整配置。
    *
    * @param dataId 配置的 dataId
    * @param group  配置分组，如 "dev"、"prod"、"DEFAULT_GROUP"
    * @return 配置项映射表，不存在时返回空 Map
    */
    Map<String, Object> get(String dataId, String group);

    /**
    * 获取指定 dataId 下某个配置项的值。
    *
    * @param dataId 配置的 dataId
    * @param key    配置项的 key
    * @return 配置项的值，不存在时返回 null
    */
    default String getValue(String dataId, String key) {
        Map<String, Object> config = get(dataId);
        if (config == null || config.isEmpty()) {
            return null;
        }
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }

    /**
    * 发布（新增/更新）单个配置项。
    *
    * @param dataId 配置的 dataId
    * @param key    配置项的 key
    * @param value  配置项的值
    * @return true-发布成功
    */
    boolean publish(String dataId, String key, String value);

    /**
    * 发布（新增/更新）单个配置项，指定分组。
    *
    * @param dataId 配置的 dataId
    * @param group  配置分组
    * @param key    配置项的 key
    * @param value  配置项的值
    * @return true-发布成功
    */
    boolean publish(String dataId, String group, String key, String value);

    /**
    * 不存在时再发布（新增/更新），避免覆盖已有配置。
    *
    * @param dataId 配置的 dataId
    * @param key    配置项的 key
    * @param value  配置项的值
    * @return true-发布成功，false-配置已存在或发布失败
    */
    default boolean publishIfAbsent(String dataId, String key, String value) {
        String existingValue = getValue(dataId, key);
        if (existingValue != null) {
            return false;
        }
        return publish(dataId, key, value);
    }

    /**
    * 不存在时再发布，指定分组。
    *
    * @param dataId 配置的 dataId
    * @param group  配置分组
    * @param key    配置项的 key
    * @param value  配置项的值
    * @return true-发布成功，false-配置已存在或发布失败
    */
    default boolean publishIfAbsent(String dataId, String group, String key, String value) {
        Map<String, Object> config = get(dataId, group);
        if (config != null && config.containsKey(key)) {
            return false;
        }
        return publish(dataId, group, key, value);
    }

    /**
    * 批量发布配置项。
    *
    * @param dataId  配置的 dataId
    * @param configs 配置项映射表
    * @return true-全部发布成功
    */
    boolean publishBatch(String dataId, Map<String, String> configs);

    /**
    * 删除指定配置项。
    *
    * @param dataId 配置的 dataId
    * @param key    配置项的 key
    * @return true-删除成功
    */
    boolean remove(String dataId, String key);

    /**
    * 是否支持发布操作。
    *
    * @return true-支持发布
    */
    boolean isSupportPublish();

    /**
    * 启动配置中心。
    *
    * <p>初始化连接、加载配置等启动操作。</p>
    */
    void start();

    /**
    * 关闭配置中心。
    *
    * <p>释放连接、清理资源等关闭操作。</p>
    *
    * @throws Exception 关闭过程中的异常
    */
    @Override
    void close() throws Exception;

    /**
    * 是否支持监听器。
    * @return true-支持监听
    */
    boolean isSupportListener();

    /**
    * 添加配置变更监听器。
    * @param dataId   配置的 dataId
    * @param listener 配置变更监听器
    */
    void addListener(String dataId, ConfigListener listener);

    /**
    * 获取默认的 dataId。
    *
    * @return 默认 dataId，为 "application"
    */
    default String getDefaultDataId() {
        return "application";
    }

    /**
    * 获取默认的分组名称。
    *
    * @return 默认分组，为 "DEFAULT_GROUP"
    */
    default String getDefaultGroup() {
        return "DEFAULT_GROUP";
    }
}
