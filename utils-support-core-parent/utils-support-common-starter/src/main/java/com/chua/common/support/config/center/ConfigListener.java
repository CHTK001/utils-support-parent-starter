package com.chua.common.support.config.center;



/**
 * 配置变更监听器。
 *
 * <p>用于监听配置中心的配置变更事件，包括新增、修改、删除操作。
 * 当配置中心的配置发生变化时，通过此接口通知监听者进行相应处理。</p>
 *
 * @author CH
 * @since 2025/11/27 11:06
 */
public interface ConfigListener {


    /**
     * 配置变更通用回调。
     *
     * <p>当配置项发生任何变化（新增、修改、删除）时触发。
     * 新增时 oldValue 为 null，删除时 newValue 为 null。</p>
     *
     * @param key      配置键（dataId）
     * @param oldValue 变更前的值
     * @param newValue 变更后的值
     */
    void onChange(String key, String oldValue, String newValue);

    /**
     * 配置删除回调。
     *
     * <p>当配置项被删除时触发。</p>
     *
     * @param key      配置键（dataId）
     * @param oldValue 被删除的旧值
     */
    void onDelete(String key, String oldValue);

    /**
     * 配置更新回调。
     *
     * <p>当配置项的值被修改时触发。</p>
     *
     * @param key      配置键（dataId）
     * @param oldValue 修改前的值
     * @param newValue 修改后的值
     */
    void onUpdate(String key, String oldValue, String newValue);
}
