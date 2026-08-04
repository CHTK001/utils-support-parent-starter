package com.chua.common.support.lang.directory.environment;

import com.chua.common.support.lang.directory.WatcherEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullUnmarked;

/**
 * 目录轮询器环境配置，承载监听参数和自定义属性。
 * <p>
 * 集中管理以下配置：
 * <ul>
 *   <li>监听的事件类型集合</li>
 *   <li>轮询间隔及时间单位</li>
 *   <li>自定义属性（数据库连接、FTP 地址、SSH 密钥等）</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class DirectoryPollerEnvironment {

    /**
     * 需要监听的事件类型
     */
    private final Set<WatcherEvent> events;

    /**
     * 轮询间隔
     */
    private final long pollingInterval;

    /**
     * 轮询间隔时间单位
     */
    private final TimeUnit timeUnit;

    /**
     * 自定义属性，通过 key-value 存储数据库/SSH/FTP 等连接参数
     */
    private final Map<String, String> properties = new LinkedHashMap<>();

    /**
     * 构造环境配置。
     *
     * @param events          需要监听的事件类型
     * @param pollingInterval 轮询间隔
     * @param timeUnit        轮询间隔时间单位
     */
    public DirectoryPollerEnvironment(Set<WatcherEvent> events, long pollingInterval, TimeUnit timeUnit) {
        this.events = events;
        this.pollingInterval = pollingInterval;
        this.timeUnit = timeUnit;
    }

    /**
     * 判断是否需要监听指定事件类型。
     *
     * @param event 事件类型
     * @return true 表示需要监听
     */
    public boolean hasEvent(WatcherEvent event) {
        return events == null || events.isEmpty() || events.contains(WatcherEvent.ALL_KIND) || events.contains(event);
    }

    /**
     * 获取轮询间隔。
     *
     * @return 轮询间隔数值
     */
    public long getPollingInterval() {
        return pollingInterval;
    }

    /**
     * 获取轮询间隔时间单位。
     *
     * @return 时间单位
     */
    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    /**
     * 获取所有已注册的事件类型。
     *
     * @return 事件类型集合
     */
    public Set<WatcherEvent> getEvents() {
        return events;
    }

    /**
     * 设置自定义属性。
     *
     * @param key   属性键
     * @param value 属性值
     * @return this
     */
    public DirectoryPollerEnvironment setProperty(String key, String value) {
        properties.put(key, value);
        return this;
    }

    /**
     * 获取自定义属性。
     *
     * @param key 属性键
     * @return 属性值，不存在返回 null
     */
    public String getProperty(String key) {
        return properties.get(key);
    }

    /**
     * 获取自定义属性，带默认值。
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 属性值，不存在返回 defaultValue
     */
    public String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    /**
     * 获取自定义属性（别名），兼容 {@code DirectoryPollerBuilder} 接口风格。
     *
     * @param key 属性键
     * @return 属性值，不存在返回 null
     */
    public String getString(String key) {
        return getProperty(key);
    }

    /**
     * 获取自定义属性（别名），兼容 {@code DirectoryPollerBuilder} 接口风格。
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 属性值，不存在返回 defaultValue
     */
    public String getString(String key, String defaultValue) {
        return getProperty(key, defaultValue);
    }

    /**
     * 获取所有自定义属性。
     *
     * @return 属性 Map
     */
    public Map<String, String> getProperties() {
        return properties;
    }
}
