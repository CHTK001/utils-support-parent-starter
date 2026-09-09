package com.chua.common.support.network.protocol.server;

import com.chua.common.support.base.bean.BeanMap;
import com.chua.common.support.network.protocol.filter.ServletFilter;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 可升级的Servlet过滤器
 * <p>
 * 继承ServletFilter接口，提供实时配置更新功能。
 * 支持通过upgrade方法动态更新过滤器配置，无需重启服务器。
 * 支持泛型配置对象和Map配置两种方式。
 * </p>
 * <p>
 * 主要功能：
 * 1. 实时配置更新 - 支持Map和泛型实体两种配置方式
 * 2. 线程安全 - 使用读写锁保证配置更新的线程安全
 * 3. 配置版本管理 - 跟踪配置版本变更
 * 4. 配置验证 - 支持配置有效性验证
 * 5. 回滚机制 - 支持配置回滚到上一个版本
 * 6. 变更通知 - 支持配置变更事件通知
 * 7. 泛型支持 - 支持强类型配置对象
 * </p>
 *
 * @param <T> 配置对象类型
 * @author CH
 * @since 2024/8/7
 */
@Slf4j
public abstract class UpgradeServletFilter<T> extends AbstractServletFilter implements ServletFilter, HotloadingServletFilter<T> {

    /**
     * 当前配置映射
     */
    private final Map<String, Object> currentConfig = new ConcurrentHashMap<>();

    /**
     * 上一版本配置映射（回滚）
     */
    private final Map<String, Object> previousConfig = new ConcurrentHashMap<>();

    /**
     * 当前配置对象
     */
    private volatile T currentConfigObject;

    /**
     * 上一版本配置对象（回滚）
     */
    private volatile T previousConfigObject;

    /**
     * 读写锁，保证配置更新的线程安全
     */
    private final ReadWriteLock configLock = new ReentrantReadWriteLock();

    /**
     * 配置版本号
     * -- GETTER --
     *  获取配置版本号

     */
    @Getter
    private volatile long configVersion = 0L;

    /**
     * 过滤器名称
     */
    protected String filterName;

    /**
     * 过滤器优先级
     * -- SETTER --
     *  设置过滤器优先级

     */
    @Setter
    private int order = 100;

    /**
     * 是否启用
     */
    private volatile boolean enabled = true;

    /**
     * 构造函数
     *
     * @param filterName 过滤器名称
     */
    protected UpgradeServletFilter(String filterName) {
        this.filterName = filterName != null ? filterName : getClass().getSimpleName();
    }

    /**
     * 构造函数（使用默认名称）
     */
    protected UpgradeServletFilter() {
        this(null);
    }

    @Override
    public final void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        if (!enabled) {
            // 如果过滤器被禁用，直接跳过
            chain.doFilter(request, response);
            return;
        }

        configLock.readLock().lock();
        try {
            // 在读锁保护下执行过滤逻辑
            if (currentConfigObject != null) {
                // 优先使用泛型配置对象
                doFilterWithConfigObject(request, response, chain, currentConfigObject);
            } else {
                // 回退到Map配置
                doFilterWithConfig(request, response, chain, currentConfig);
            }
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * 带配置的过滤器处理方法（Map配置）
     * <p>
     * 子类可以实现此方法来处理Map配置的过滤逻辑。
     * 此方法在读锁保护下执行，可以安全地访问配置信息。
     * 如果子类同时实现了doFilterWithConfigObject方法，则优先使用泛型配置对象。
     * </p>
     *
     * @param request 请求对象
     * @param response 响应对象
     * @param chain 过滤器链
     * @param config 当前配置映射
     * @throws Exception 处理过程中可能抛出的异常
     */
    protected void doFilterWithConfig(ServletRequest request, ServletResponse response,
                                      ServletFilterChain chain, Map<String, Object> config) throws Exception {
        // 默认实现：如果没有泛型配置对象，则抛出异常要求子类实现
        if (currentConfigObject == null) {
            throw new UnsupportedOperationException("子类必须实现 doFilterWithConfig 或 doFilterWithConfigObject 方法");
        }
        // 如果有泛型配置对象，则调用泛型方法
        doFilterWithConfigObject(request, response, chain, currentConfigObject);
    }

    /**
     * 带配置对象的过滤器处理方法（泛型配置）
     * <p>
     * 子类可以实现此方法来处理泛型配置对象的过滤逻辑。
     * 此方法在读锁保护下执行，可以安全地访问配置信息。
     * 这是推荐的实现方式，提供强类型支持。
     * </p>
     *
     * @param request 请求对象
     * @param response 响应对象
     * @param chain 过滤器链
     * @param configObject 当前配置对象
     * @throws Exception 处理过程中可能抛出的异常
     */
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response,
                                            ServletFilterChain chain, T configObject) throws Exception {
        // 默认实现：回退到Map配置
        doFilterWithConfig(request, response, chain, currentConfig);
    }

    /**
     * 升级配置（Map方式）
     * <p>
     * 实时更新过滤器配置，支持热更新。
     * 此方法是线程安全的，可以在运行时安全调用。
     * </p>
     *
     * @param newConfig 新的配置映射
     * @return 是否升级成功
     */
    public boolean upgrade(Map<String, Object> newConfig) {
        if (newConfig == null) {
            log.warn("升级配置为null，忽略升级请求: {}", filterName);
            return false;
        }

        configLock.writeLock().lock();
        try {
            // 验证新配置
            if (!validateConfig(newConfig)) {
                log.warn("配置验证失败，升级被拒绝: {}", filterName);
                return false;
            }

            // 备份当前配置
            previousConfig.clear();
            previousConfig.putAll(currentConfig);

            // 应用新配置
            currentConfig.clear();
            currentConfig.putAll(newConfig);

            // 增加版本号
            configVersion++;

            log.info("配置升级成功: {} -> @version  {}", filterName, configVersion);

            // 通知配置变更
            onConfigurationChanged(previousConfig, currentConfig);

            return true;

        } catch (Exception e) {
            log.error("配置升级失败: {}", filterName, e);
            return false;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    /**
     * 升级配置（泛型对象方式）
     * <p>
     * 实时更新过滤器配置对象，支持热更新。
     * 此方法是线程安全的，可以在运行时安全调用。
     * 提供强类型支持，推荐使用此方法。
     * </p>
     *
     * @param newConfigObject 新的配置对象
     * @return 是否升级成功
     */
    public boolean upgradeConfigObject(T newConfigObject) {
        if (newConfigObject == null) {
            log.warn("升级配置对象为null，忽略升级请求: {}", filterName);
            return false;
        }

        configLock.writeLock().lock();
        try {
            // 验证新配置对象
            if (!validateConfigObject(newConfigObject)) {
                log.warn("配置对象验证失败，升级被拒绝: {}", filterName);
                return false;
            }

            // 备份当前配置对象
            previousConfigObject = currentConfigObject;

            // 应用新配置对象
            currentConfigObject = newConfigObject;

            // 同时更新Map配置（如果支持转换）
            try {
                Map<String, Object> newConfigMap = convertConfigObjectToMap(newConfigObject);
                if (newConfigMap != null) {
                    previousConfig.clear();
                    previousConfig.putAll(currentConfig);
                    currentConfig.clear();
                    currentConfig.putAll(newConfigMap);
                }
            } catch (Exception e) {
                log.warn("配置对象转换为Map失败，仅更新配置对象: {}", e.getMessage());
            }

            // 增加版本号
            configVersion++;

            log.info("配置对象升级成功: {} -> @version  {}", filterName, configVersion);

            // 通知配置变更
            onConfigurationObjectChanged(previousConfigObject, currentConfigObject);

            return true;

        } catch (Exception e) {
            log.error("配置对象升级失败: {}", filterName, e);
            return false;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    /**
     * 回滚配置到上一个版本
     *
     * @return 是否回滚成功
     */
    public boolean rollback() {
        if (previousConfig.isEmpty() && previousConfigObject == null) {
            log.warn("没有可回滚的配置: {}", filterName);
            return false;
        }

        configLock.writeLock().lock();
        try {
            // 回滚配置对象
            if (previousConfigObject != null) {
                T temp = currentConfigObject;
                currentConfigObject = previousConfigObject;
                previousConfigObject = temp;
            }

            // 回滚Map配置
            if (!previousConfig.isEmpty()) {
                Map<String, Object> temp = new ConcurrentHashMap<>(currentConfig);
                currentConfig.clear();
                currentConfig.putAll(previousConfig);
                previousConfig.clear();
                previousConfig.putAll(temp);
            }

            // 增加版本号
            configVersion++;

            log.info("配置回滚成功: {} -> @version  {}", filterName, configVersion);

            // 通知配置变更
            if (currentConfigObject != null) {
                onConfigurationObjectChanged(previousConfigObject, currentConfigObject);
            } else {
                onConfigurationChanged(previousConfig, currentConfig);
            }

            return true;

        } catch (Exception e) {
            log.error("配置回滚失败: {}", filterName, e);
            return false;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    /**
     * 获取当前配置的副本
     *
     * @return 当前配置的副本
     */
    public Map<String, Object> getCurrentConfig() {
        configLock.readLock().lock();
        try {
            return new ConcurrentHashMap<>(currentConfig);
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * 获取当前配置对象
     *
     * @return 当前配置对象
     */
    @Override
    public T getConfigurationObject() {
        configLock.readLock().lock();
        try {
            return currentConfigObject;
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * 更新配置对象
     *
     * @param config 配置对象
     */
    @Override
    public void updateConfigurationObject(T config) {
        upgradeConfigObject(config);
    }

    /**
     * 获取配置值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @param <T> 值类型
     * @return 配置值
     */
    @SuppressWarnings("unchecked")
    protected <T> T getConfigValue(String key, T defaultValue) {
        configLock.readLock().lock();
        try {
            Object value = currentConfig.get(key);
            if (value == null) {
                return defaultValue;
            }
            try {
                return (T) value;
            } catch (ClassCastException e) {
                log.warn("配置值类型转换失败: {} -> {}, 使用默认值", key, value, e);
                return defaultValue;
            }
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * 获取字符串配置值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    protected String getStringConfig(String key, String defaultValue) {
        Object value = getConfigValue(key, defaultValue);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 获取布尔配置值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        Object value = getConfigValue(key, defaultValue);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    /**
     * 获取整数配置值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    protected int getIntConfig(String key, int defaultValue) {
        Object value = getConfigValue(key, defaultValue);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                log.warn("配置值不是有效的整数: {} -> {}, 使用默认值: {}", key, value, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * 获取长整数配置值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    protected long getLongConfig(String key, long defaultValue) {
        Object value = getConfigValue(key, defaultValue);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                log.warn("配置值不是有效的长整数: {} -> {}, 使用默认值: {}", key, value, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * 设置过滤器启用状态
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("过滤器启用状态变更: {} -> {}", filterName, enabled ? "启用" : "禁用");
    }

    /**
     * 验证配置有效性
     * <p>
     * 子类可以重写此方法来实现自定义的配置验证逻辑。
     * 默认实现总是返回true。
     * </p>
     *
     * @param config 待验证的配置
     * @return 配置是否有效
     */
    protected boolean validateConfig(Map<String, Object> config) {
        return true;
    }

    /**
     * 配置变更通知（Map配置）
     * <p>
     * 当Map配置发生变更时调用此方法。
     * 子类可以重写此方法来处理配置变更事件。
     * </p>
     *
     * @param oldConfig 旧配置
     * @param newConfig 新配置
     */
    protected void onConfigurationChanged(Map<String, Object> oldConfig, Map<String, Object> newConfig) {
        // 默认空实现，子类可以重写
    }

    /**
     * 配置对象变更通知（泛型配置）
     * <p>
     * 当配置对象发生变更时调用此方法。
     * 子类可以重写此方法来处理配置变更事件。
     * </p>
     *
     * @param oldConfigObject 旧配置对象
     * @param newConfigObject 新配置对象
     */
    protected void onConfigurationObjectChanged(T oldConfigObject, T newConfigObject) {
        // 默认空实现，子类可以重写
    }

    /**
     * 验证配置对象有效性
     * <p>
     * 子类可以重写此方法来实现自定义的配置对象验证逻辑。
     * 默认实现总是返回true。
     * </p>
     *
     * @param configObject 待验证的配置对象
     * @return 配置对象是否有效
     */
    protected boolean validateConfigObject(T configObject) {
        return true;
    }

    /**
     * 将配置对象转换为Map
     * <p>
     * 子类可以重写此方法来实现配置对象到Map的转换。
     * 默认实现使用MapUtils进行转换。
     * </p>
     *
     * @param configObject 配置对象
     * @return 转换后的Map，如果转换失败返回null
     */
    protected Map<String, Object> convertConfigObjectToMap(T configObject) {
        try {
            return BeanMap.of(configObject);
        } catch (Exception e) {
            log.warn("配置对象转换为Map失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将Map转换为配置对象
     * <p>
     * 子类可以重写此方法来实现Map到配置对象的转换。
     * 默认实现返回null，表示不支持转换。
     * </p>
     *
     * @param configMap 配置Map
     * @return 转换后的配置对象，如果转换失败返回null
     */
    protected T convertMapToConfigObject(Map<String, Object> configMap) {
        // 默认实现不支持转换，子类需要重写此方法
        return null;
    }

    @Override
    public String getFilterName() {
        return filterName;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getDescription() {
        return String.format("可升级过滤器: %s (@version  %d)", filterName, configVersion);
    }

    /**
     * 获取过滤器支持的配置选项列表
     * <p>
     * 返回此过滤器支持的所有配置项信息，包括配置名称、类型、描述等。
     * 子类可以重写此方法来自定义配置选项。
     * </p>
     *
     * @return 配置选项列表
     */
    public List<FilterOption> getFilterOptions() {
        // 默认实现返回空列表，子类需要重写此方法来提供具体的配置选项
        return new ArrayList<>();
    }

    /**
     * 根据配置选项验证配置对象
     * 
     * @param config 待验证的配置对象
     * @param options 配置选项列表
     * @return 验证结果，包含错误信息（如果有）
     */
    protected Map<String, String> validateConfigWithOptions(Map<String, Object> config, List<FilterOption> options) {
        Map<String, String> errors = new HashMap<>();
        
        for (FilterOption option : options) {
            String key = option.getKey();
            Object value = config.get(key);
            
            // 检查必需项
            if (option.isRequired() && value == null) {
                errors.put(key, "必需的配置项未提供");
                continue;
            }
            
            // 如果有值，检查类型
            if (value != null && !isValueTypeValid(value, option.getType())) {
                errors.put(key, String.format("配置项类型不匹配，期望: %s，实际: %s",
                    option.getType().getSimpleName(),
                    value.getClass().getSimpleName()));
            }
        }
        
        return errors;
    }
    
    /**
     * 检查值的类型是否匹配预期类型
     */
    private boolean isValueTypeValid(Object value, Class<?> expectedType) {
        if (expectedType == String.class) {
            return true; // 所有值都可以转换为字符串
        }
        
        if (expectedType == Integer.class || expectedType == int.class) {
            return value instanceof Integer || value instanceof String && isInteger((String) value);
        }
        
        if (expectedType == Long.class || expectedType == long.class) {
            return value instanceof Long || value instanceof Integer || 
                   value instanceof String && isLong((String) value);
        }
        
        if (expectedType == Boolean.class || expectedType == boolean.class) {
            return value instanceof Boolean || value instanceof String &&
                   ("true".equalsIgnoreCase((String) value) || "false".equalsIgnoreCase((String) value));
        }
        
        return expectedType.isInstance(value);
    }
    
    private boolean isInteger(String str) {
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    private boolean isLong(String str) {
        try {
            Long.parseLong(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

}
