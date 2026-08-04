package com.chua.common.support.objects.environment;

import com.chua.common.support.collection.SortedArrayList;
import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NullUnmarked;

/**
 * 默认环境配置实现。
 *
 * <p>基于内存 Map 存储手动设置的配置属性，并聚合多个 {@link PropertySource} 的配置数据。
 * 配置查找严格按照优先级顺序进行，优先级高的配置值会覆盖优先级低的配置值。</p>
 *
 * <p>配置查找优先级（从高到低）：
 * <ol>
 *   <li>手动设置的属性（通过 {@link #setProperty} 设置）</li>
 *   <li>各 ConfigSource 中的属性（按 {@link PropertySource#getPriority()} 从高到低）</li>
 * </ol>
 *
 * <p>支持 ${key} 占位符语法，如设置 "server.url=http://${server.host}:${server.port}"，
 * 获取时会自动解析为对应的配置值。占位符解析支持递归嵌套，最多递归 100 层以防止无限循环。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 *   DefaultEnvironment env = new DefaultEnvironment();
 *   env.setProperty("app.name", "my-app");
 *   env.addConfigSource(new PropertiesConfigSource("application.properties"));
 *   String name = env.getProperty("app.name"); // "my-app"
 * }</pre>
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
@Slf4j
public class DefaultEnvironment implements Environment {

    /**
     * 占位符解析的最大递归深度，防止因循环引用导致无限递归。
     */
    private static final int MAX_PLACEHOLDER_ITERATIONS = 100;

    /**
     * 手动设置的属性（最高优先级）。
     *
     * <p>使用 {@link ConcurrentHashMap} 保证线程安全，
     * 支持高并发场景下的读写操作。</p>
     */
    private final Map<String, Object> manualProperties = new ConcurrentHashMap<>();

    /**
     * 配置源列表，按优先级降序排列。
     *
     * <p>使用 {@link SortedArrayList} 实现，添加元素时自动按优先级从高到低排序。
     * 配置查找时按此顺序遍历，找到即返回（快速返回策略）。</p>
     */
    private final List<PropertySource> propertySources = new SortedArrayList<>(
            Comparator.comparingInt(PropertySource::getPriority).reversed());

    /**
     * 配置变更监听器列表。
     *
     * <p>使用 {@link CopyOnWriteArrayList} 保证遍历时线程安全，
     * 避免在通知监听器过程中因并发修改而抛出异常。</p>
     */
    private final List<EnvironmentChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 创建空环境配置。
     *
     * <p>构造后不包含任何配置属性，需要手动调用
     * {@link #setProperty} 或 {@link #addConfigSource} 来添加配置数据。</p>
     */
    public DefaultEnvironment() {
        loadConfigSourceProviders();
    }

    /**
     * 创建带初始属性的环境配置。
     *
     * <p>初始属性作为手动设置的属性存入，具有最高优先级。
     * 如果初始属性为 null，则等同于调用无参构造函数。</p>
     *
     * @param initialProperties 初始属性映射表，可为 null
     */
    public DefaultEnvironment(Map<String, Object> initialProperties) {
        if (initialProperties != null) {
            manualProperties.putAll(initialProperties);
        }
        loadConfigSourceProviders();
    }

    /**
     * 通过 SPI 加载 {@link ConfigSourceProvider}，自动注册配置源。
     */
    private void loadConfigSourceProviders() {
        try {
            var providers = ServiceProvider.of(ConfigSourceProvider.class).collect();
            for (ConfigSourceProvider provider : providers) {
                List<PropertySource> sources = provider.getPropertySources();
                if (sources != null) {
                    for (PropertySource source : sources) {
                        addConfigSource(source);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载 ConfigSourceProvider 失败", e);
        }
    }

    @Override
    public String getProperty(String key) {
        return getProperty(key, String.class, null);
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        String value = getProperty(key, String.class, null);
        if (value != null) {
            return value;
        }
        return defaultValue;
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType) {
        return getProperty(key, targetType, null);
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
        // 参数校验：key 和 targetType 均不可为 null
        if (key == null || targetType == null) {
            return defaultValue;
        }
        // 步骤一：优先从手动设置的属性中查找
        Object value = manualProperties.get(key);
        if (value != null) {
            // 如果原始值类型与目标类型匹配，直接转换返回
            if (targetType.isInstance(value)) {
                return targetType.cast(value);
            }
            // 否则通过 Converter 进行类型转换
            return Converter.convertIfNecessary(value, targetType);
        }
        // 步骤二：从配置源中查找（按优先级从高到低遍历）
        synchronized (propertySources) {
            for (PropertySource source : propertySources) {
                Object sourceValue = source.getProperty(key);
                if (sourceValue != null) {
                    if (sourceValue instanceof String) {
                        String strValue = (String) sourceValue;
                        strValue = resolvePlaceholders(strValue);
                        if (targetType == String.class) {
                            return targetType.cast(strValue);
                        }
                        return Converter.convertIfNecessary(strValue, targetType);
                    }
                    if (targetType.isInstance(sourceValue)) {
                        return targetType.cast(sourceValue);
                    }
                    return Converter.convertIfNecessary(sourceValue, targetType);
                }
            }
        }
        // 步骤三：所有来源均未找到，返回默认值
        return defaultValue;
    }

    @Override
    public void setProperty(String key, Object value) {
        // 参数校验：key 不可为 null
        if (key == null) {
            return;
        }
        // 存储旧值用于变更通知
        Object oldValue = manualProperties.put(key, value);
        // 仅当值确实发生变化时才通知监听器
        if (!Objects.equals(oldValue, value)) {
            notifyListeners(key, oldValue, value);
        }
    }

    @Override
    public boolean containsProperty(String key) {
        // 参数校验：key 不可为 null
        if (key == null) {
            return false;
        }
        // 步骤一：检查手动设置的属性
        if (manualProperties.containsKey(key)) {
            return true;
        }
        // 步骤二：检查所有配置源
        synchronized (propertySources) {
            for (PropertySource source : propertySources) {
                if (source.getProperty(key) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void addChangeListener(EnvironmentChangeListener listener) {
        // 参数校验：listener 不可为 null
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeChangeListener(EnvironmentChangeListener listener) {
        // 参数校验：listener 不可为 null
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public void addConfigSource(PropertySource propertySource) {
        if (propertySource == null) {
            return;
        }
        synchronized (propertySources) {
            propertySources.add(propertySource);
        }
        log.debug("添加配置源: {} (优先级: {})", propertySource.getName(), propertySource.getPriority());
    }

    @Override
    public void removeConfigSource(PropertySource propertySource) {
        if (propertySource == null) {
            return;
        }
        synchronized (propertySources) {
            propertySources.remove(propertySource);
        }
        log.debug("移除配置源: {}", propertySource.getName());
    }


    @Override
    public void refresh() {
        // 步骤一：依次刷新所有配置源
        for (PropertySource propertySource : propertySources) {
            try {
                propertySource.refresh();
                log.debug("刷新配置源: {}", propertySource.getName());
            } catch (Exception e) {
                // 单个配置源刷新失败不影响其他配置源
                log.warn("刷新配置源失败: {}", propertySource.getName(), e);
            }
        }
        // 步骤二：通知所有监听器配置已刷新
        for (EnvironmentChangeListener listener : listeners) {
            try {
                listener.onChange(null, null, null);
            } catch (Exception e) {
                // 单个监听器异常不影响其他监听器
                log.warn("通知配置变更监听器失败: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * 批量设置属性。
     *
     * <p>遍历传入的 Map，依次调用 {@link #setProperty} 设置每个属性。
     * 每次设置都会触发配置变更监听器通知。
     * 如果传入的 Map 为 null 或空，则此方法不做任何事情。</p>
     *
     * @param props 属性映射表，可为 null
     */
    public void putAll(Map<String, Object> props) {
        if (props == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            setProperty(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 解析 ${key} 占位符。
     *
     * <p>递归解析字符串中的 ${...} 占位符，替换为对应的配置值。
     * 占位符查找顺序：先查手动属性，再按优先级查配置源。
     * 如果找不到对应的配置值，则将占位符替换为空字符串。
     * 递归深度受 {@link #MAX_PLACEHOLDER_ITERATIONS} 限制，防止无限循环。</p>
     *
     * @param value 包含占位符的字符串
     * @return 解析后的字符串
     */
    private String resolvePlaceholders(String value) {
        // 参数校验：value 不可为 null
        if (value == null) {
            return null;
        }
        String result = value;
        int start = result.indexOf("${");
        int iteration = 0;
        // 循环解析所有占位符，直到没有更多占位符或达到最大递归深度
        while (start >= 0 && iteration < MAX_PLACEHOLDER_ITERATIONS) {
            int end = result.indexOf("}", start);
            // 如果找不到结束符，则退出循环
            if (end < 0) {
                break;
            }
            // 提取占位符中的 key（去掉 ${ 和 }）
            String placeholder = result.substring(start + 2, end);
            // 查找占位符对应的配置值：先查手动属性，再查配置源
            String resolved = resolvePlaceholderValue(placeholder);
            String replacement = (resolved != null) ? resolved : "";
            // 替换占位符为实际值
            result = result.substring(0, start) + replacement + result.substring(end + 1);
            // 继续查找下一个占位符
            start = result.indexOf("${", start + replacement.length());
            iteration++;
        }
        return result;
    }

    /**
     * 查找占位符对应的配置值。
     *
     * <p>查找顺序：
     * <ol>
     *   <li>手动设置的属性</li>
     *   <li>各 ConfigSource 中的属性（按优先级从高到低）</li>
     * </ol>
     * 如果所有来源均未找到，则返回 null。</p>
     *
     * @param placeholder 占位符 key（不含 ${} 包裹）
     * @return 配置值，未找到时返回 null
     */
    private String resolvePlaceholderValue(String placeholder) {
        // 步骤一：从手动设置的属性中查找
        Object manualValue = manualProperties.get(placeholder);
        if (manualValue != null) {
            return manualValue.toString();
        }
        // 步骤二：从配置源中查找（按优先级从高到低）
        for (PropertySource source : propertySources) {
            Object sourceValue = source.getProperty(placeholder);
            if (sourceValue != null) {
                return sourceValue.toString();
            }
        }
        return null;
    }

    /**
     * 通知所有配置变更监听器。
     *
     * <p>遍历所有已注册的监听器，依次调用 {@link EnvironmentChangeListener#onChange} 方法。
     * 单个监听器抛出异常不会影响其他监听器的通知。
     * 如果 key 为 null，表示批量刷新通知。</p>
     *
     * @param key      变更的配置键
     * @param oldValue 旧值
     * @param newValue 新值
     */
    private void notifyListeners(String key, Object oldValue, Object newValue) {
        for (EnvironmentChangeListener listener : listeners) {
            try {
                listener.onChange(key, oldValue, newValue);
            } catch (Exception e) {
                // 单个监听器异常不影响其他监听器
                log.warn("通知配置变更监听器失败: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
}