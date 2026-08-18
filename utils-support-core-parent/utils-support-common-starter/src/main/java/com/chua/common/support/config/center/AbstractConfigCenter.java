package com.chua.common.support.config.center;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置中心抽象基类。
 *
 * <p>提供配置中心的通用骨架实现，包括监听器管理、启动/关闭日志、
 * 默认的发布和移除操作实现。子类需实现具体的配置获取逻辑。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Slf4j
public abstract class AbstractConfigCenter implements ConfigCenter {

    /**
     * 配置中心连接设置。
     */
    protected final ConfigCenterSetting configCenterSetting;
    /** 配置监听器列表 */
    /** Listeners */
    protected List<ConfigListener> listeners;

    /**
     * 构造配置中心。
     *
     * @param configCenterSetting 配置中心连接设置
     */
    public AbstractConfigCenter(ConfigCenterSetting configCenterSetting) {
        this.configCenterSetting = configCenterSetting;
        this.listeners = new ArrayList<>();
    }

    /**
     * 记录启动日志。
     */
    protected void logStartup() {
        log.info("配置中心启动成功，地址: {}", configCenterSetting.getAddress());
    }

    /**
     * 记录关闭日志。
     */
    protected void logShutdown() {
        log.info("配置中心已关闭，地址: {}", configCenterSetting.getAddress());
    }

    @Override
    public boolean isSupportListener() {
        return true;
    }

    @Override
    public boolean isSupportPublish() {
        return true;
    }

    @Override
    public boolean publish(String dataId, String key, String value) {
        return publish(dataId, "DEFAULT_GROUP", key, value);
    }

    @Override
    public boolean publish(String dataId, String group, String key, String value) {
        log.warn("当前配置中心不支持发布操作: {}", this.getClass().getSimpleName());
        return false;
    }

    @Override
    public boolean publishBatch(String dataId, Map<String, String> configs) {
        if (configs == null || configs.isEmpty()) {
            return true;
        }
        boolean success = true;
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            if (!publish(dataId, entry.getKey(), entry.getValue())) {
                success = false;
            }
        }
        return success;
    }

    @Override
    public boolean remove(String dataId, String key) {
        log.warn("当前配置中心不支持移除操作: {}", this.getClass().getSimpleName());
        return false;
    }

    @Override
    public void addListener(String dataId, ConfigListener listener) {
        this.listeners.add(listener);
    }

    /**
     * 通知监听器 - 配置项已删除。
     *
     * @param key      被删除的配置键
     * @param oldValue 被删除的旧值
     */
    public void notifyListenerDelete(String key, String oldValue) {
        for (ConfigListener listener : listeners) {
            listener.onDelete(key, oldValue);
            listener.onChange(key, oldValue, null);
        }
    }

    /**
     * 通知监听器 - 配置项已更新。
     *
     * @param key      更新的配置键
     * @param newValue 更新后的新值
     * @param oldValue 更新前的旧值
     */
    public void notifyListenerUpdate(String key, String newValue, String oldValue) {
        for (ConfigListener listener : listeners) {
            listener.onChange(key, oldValue, newValue);
            listener.onUpdate(key, oldValue, newValue);
        }
    }
}
