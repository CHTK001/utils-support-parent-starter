package com.chua.common.support.objects.environment;


/**
 * 环境配置变更监听器。
 *
 * <p>用于监听环境配置属性的变更事件。当通过 {@link Environment#setProperty} 设置属性、
 * 或调用 {@link Environment#refresh} 刷新配置源时，所有注册的监听器会被通知。</p>
 *
 * <p>应用场景：
 * <ul>
 *   <li>配置热加载：配置变更后重新初始化相关组件</li>
 *   <li>动态刷新：监听配置变化并刷新缓存</li>
 *   <li>日志记录：记录所有配置变更操作</li>
 * </ul></p>
 *
 * @author CH
 * @since 2024/12/20
 */
public interface EnvironmentChangeListener {

    /**
     * 配置变更回调。
     *
     * <p>当配置属性发生变化时被调用。key、oldValue、newValue 都可能为 null。
     * 例如 {@link Environment#refresh} 会触发 键 为 空 的变更通知，表示批量刷新。</p>
     *
     * @param key      变更的配置键，空 表示批量刷新
     * @param oldValue 变更前的值
     * @param newValue 变更后的值
     */
    void onChange(String key, Object oldValue, Object newValue);
}
