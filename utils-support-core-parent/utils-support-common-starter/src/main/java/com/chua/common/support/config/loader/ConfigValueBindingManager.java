package com.chua.common.support.config.loader;

import com.chua.common.support.config.center.ConfigListener;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.objects.annotation.ConfigValue;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * 配置值绑定管理器。
 *
 * <p>管理 @ConfigValue 注解的扫描、注册和动态更新。
 * 支持配置热加载，当配置中心的数据发生变化时自动更新绑定值。</p>
 *
 * <h3>核心流程</h3>
 * <ul>
 *     <li>扫描 Bean 中标注 @ConfigValue 的字段和方法并注册</li>
 *     <li>从配置提供者获取配置值并注入到目标字段</li>
 *     <li>监听配置变更，自动更新热加载的绑定值</li>
 *     <li>支持回调方法，在配置变更后执行自定义逻辑</li>
 * </ul>
 *
 * @author CH
 * @since 2024-12-05
 * @version 1.0.0
 */
@Slf4j
public class ConfigValueBindingManager implements ConfigListener {

    /**
     * 占位符正则表达式。
     * 匹配 ${key} 和 ${key:defaultValue} 两种格式。
     */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    /**
     * 按配置键分组的绑定映射表。
     * key: 配置键
     * value: 该配置键对应的所有绑定列表
     */
    private final Map<String, List<ConfigValueBinding>> bindingsByKey = new ConcurrentHashMap<>();

    /**
     * 全部绑定的列表。
     */
    private final List<ConfigValueBinding> allBindings = Collections.synchronizedList(new ArrayList<>());

    /**
     * 配置值提供者函数。
     */
    private Function<String, String> configProvider;

    /**
     * 全局单例实例。
     */
    private static volatile ConfigValueBindingManager instance;

    /**
     * 获取全局单例。
     *
     * @return 单例实例
     */
    public static ConfigValueBindingManager getInstance() {
        if (instance == null) {
            synchronized (ConfigValueBindingManager.class) {
                if (instance == null) {
                    instance = new ConfigValueBindingManager();
                }
            }
        }
        return instance;
    }

    /** 创建 ConfigValueBindingManager 实例 */
    private ConfigValueBindingManager() {
    }

    /**
     * 设置配置值提供者。
     *
     * @param provider 根据 key 返回配置值的函数
     */
    public void setConfigProvider(Function<String, String> provider) {
        this.configProvider = provider;
    }

    /**
     * 扫描 Bean 中标注了 @ConfigValue 的字段和方法并注册。
     *
     * @param beanName Bean 名称
     * @param bean     Bean 实例
     */
    public void scanAndRegister(String beanName, Object bean) {
        if (bean == null) {
            return;
        }

        Class<?> clazz = bean.getClass();
        for (Field field : getAllFields(clazz)) {
            ConfigValue annotation = field.getAnnotation(ConfigValue.class);
            if (annotation != null) {
                registerFieldBinding(beanName, bean, field, annotation);
            }
        }

        List<Method> methods = ClassUtils.getLocalMethods(bean);
        for (Method method : methods) {
            ConfigValue annotation = method.getAnnotation(ConfigValue.class);
            if (annotation != null && method.getParameterCount() == 1) {
                registerMethodBinding(beanName, bean, method, annotation);
            }
        }
    }

    /**
     * 注册字段绑定。
     *
     * @param beanName   Bean 名称
     * @param bean       Bean 实例
     * @param field      目标字段
     * @param annotation @ConfigValue 注解
     */
    private void registerFieldBinding(String beanName, Object bean, Field field, ConfigValue annotation) {
        String expression = annotation.value();
        ParsedExpression parsed = parseExpression(expression);

        ConfigValueBinding binding = ConfigValueBinding.builder()
                .configKey(parsed.key)
                .expression(expression)
                .defaultValue(parsed.defaultValue)
                .bean(bean)
                .beanName(beanName)
                .field(field)
                .hotReload(annotation.hotReload())
                .callback(annotation.callback())
                .targetType(field.getType())
                .build();

        addBinding(binding);

        //             
        String value = resolveValue(parsed.key, parsed.defaultValue);
        injectFieldValue(binding, value);

        if (log.isDebugEnabled()) {
            log.debug("注册字段绑定: {}", binding);
        }
    }

    /**
     * 注册方法绑定。
     *
     * @param beanName   Bean 名称
     * @param bean       Bean 实例
     * @param method     目标方法
     * @param annotation @ConfigValue 注解
     */
    private void registerMethodBinding(String beanName, Object bean, Method method, ConfigValue annotation) {
        String expression = annotation.value();
        ParsedExpression parsed = parseExpression(expression);

        ConfigValueBinding binding = ConfigValueBinding.builder()
                .configKey(parsed.key)
                .expression(expression)
                .defaultValue(parsed.defaultValue)
                .bean(bean)
                .beanName(beanName)
                .method(method)
                .hotReload(annotation.hotReload())
                .callback(annotation.callback())
                .targetType(method.getParameterTypes()[0])
                .build();

        addBinding(binding);

        //             
        String value = resolveValue(parsed.key, parsed.defaultValue);
        injectMethodValue(binding, value);

        if (log.isDebugEnabled()) {
            log.debug("注册方法绑定: {}", binding);
        }
    }

    /**
     * 添加绑定到注册表。
     *
     * @param binding 配置值绑定
     */
    private void addBinding(ConfigValueBinding binding) {
        allBindings.add(binding);
        bindingsByKey.computeIfAbsent(binding.getConfigKey(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(binding);
    }

    /**
     * 解析配置值。
     *
     * @param key           配置键
     * @param defaultValue  默认值
     * @return 配置值
     */
    private String resolveValue(String key, String defaultValue) {
        if (configProvider != null) {
            String value = configProvider.apply(key);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    /**
     * 将配置值注入到字段。
     *
     * @param binding 配置值绑定
     * @param value   配置值
     */
    private void injectFieldValue(ConfigValueBinding binding, String value) {
        try {
            Field field = binding.getField();
            Object convertedValue = convertValue(value, binding.getTargetType());
            ReflectUtils.setField(binding.getBean(), field.getName(), convertedValue);
            binding.setCurrentValue(convertedValue);
        } catch (Exception e) {
            log.error("注入字段配置值失败: {}", binding, e);
        }
    }

    /**
     * 将配置值注入到方法。
     *
     * @param binding 配置值绑定
     * @param value   配置值
     */
    private void injectMethodValue(ConfigValueBinding binding, String value) {
        try {
            Method method = binding.getMethod();
            ClassUtils.setAccessible(method);
            Object convertedValue = convertValue(value, binding.getTargetType());
            ReflectUtils.invoke(binding.getBean(), method.getName(), method.getReturnType(), method.getParameterTypes(), convertedValue);
            binding.setCurrentValue(convertedValue);
        } catch (Exception e) {
            log.error("注入方法配置值失败: {}", binding, e);
        }
    }

    /**
     * 类型转换。
     *
     * @param value      原始字符串值
     * @param targetType 目标类型
     * @return 转换后的对象
     */
    private Object convertValue(String value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        return Converter.convertIfNecessary(value, targetType);
    }

    /**
     * 解析占位符表达式。
     *
     * @param expression 占位符表达式，如 "${server.port:8080}"
     * @return 解析结果，包含 key 和默认值
     */
    private ParsedExpression parseExpression(String expression) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(expression);
        if (matcher.find()) {
            String key = matcher.group(1);
            String defaultValue = matcher.group(2);
            return new ParsedExpression(key, defaultValue);
        }
        // 如果表达式不包含占位符，直接作为 key 处理
        return new ParsedExpression(expression, null);
    }

    /**
     * 获取类的所有字段。
     *
     * @param clazz 目标类
     * @return 字段列表
     */
    private List<Field> getAllFields(Class<?> clazz) {
        return ClassUtils.getFields(clazz);
    }

    /**
     * 获取所有启用了热加载的绑定列表。
     *
     * @return 热加载绑定列表
     */
    public List<ConfigValueBinding> getHotReloadBindings() {
        List<ConfigValueBinding> result = new ArrayList<>();
        for (ConfigValueBinding binding : allBindings) {
            if (binding.isHotReload()) {
                result.add(binding);
            }
        }
        return result;
    }

    /**
     * 按配置键获取绑定列表。
     *
     * @param configKey 配置键
     * @return 绑定列表
     */
    public List<ConfigValueBinding> getBindingsByKey(String configKey) {
        return bindingsByKey.getOrDefault(configKey, Collections.emptyList());
    }

    // ==================== ConfigListener 接口实现 ====================

    @Override
    /**
     * OnChange
     * @param key key
     * @param oldValue oldValue
     * @param newValue newValue
     */
    public void onChange(String key, String oldValue, String newValue) {
        // 由 onUpdate 处理即可
    }

    @Override
    /**
     * On删除
     * @param key key
     * @param oldValue oldValue
     */
    public void onDelete(String key, String oldValue) {
        List<ConfigValueBinding> bindings = bindingsByKey.get(key);
        if (bindings == null || bindings.isEmpty()) {
            return;
        }

        for (ConfigValueBinding binding : bindings) {
            if (!binding.isHotReload()) {
                continue;
            }

            String defaultValue = binding.getDefaultValue();
            updateBindingValue(binding, oldValue, defaultValue);
        }
    }

    @Override
    /**
     * On更新
     * @param key key
     * @param oldValue oldValue
     * @param newValue newValue
     */
    public void onUpdate(String key, String oldValue, String newValue) {
        List<ConfigValueBinding> bindings = bindingsByKey.get(key);
        if (bindings == null || bindings.isEmpty()) {
            return;
        }

        for (ConfigValueBinding binding : bindings) {
            if (!binding.isHotReload()) {
                if (log.isDebugEnabled()) {
                    log.debug("配置 [{}] 已变更，但绑定 [{}] 未启用热加载，跳过更新", key, binding);
                }
                continue;
            }

            updateBindingValue(binding, oldValue, newValue);
        }
    }

    /**
     * 更新绑定的配置值。
     *
     * @param binding  配置值绑定
     * @param oldValue 旧值
     * @param newValue 新值
     */
    private void updateBindingValue(ConfigValueBinding binding, String oldValue, String newValue) {
        Object oldConvertedValue = binding.getCurrentValue();

        if (binding.isFieldBinding()) {
            injectFieldValue(binding, newValue);
        } else if (binding.isMethodBinding()) {
            injectMethodValue(binding, newValue);
        }

        log.info("配置值已更新: key={}, bean={}, oldValue={}, newValue={}",
                binding.getConfigKey(), binding.getBeanName(), oldValue, newValue);

        invokeCallback(binding, oldConvertedValue, binding.getCurrentValue());
    }

    /**
     * 调用配置变更回调方法。
     *
     * @param binding  配置值绑定
     * @param oldValue 旧值
     * @param newValue 新值
     */
    private void invokeCallback(ConfigValueBinding binding, Object oldValue, Object newValue) {
        String callbackName = binding.getCallback();
        if (StringUtils.isEmpty(callbackName)) {
            return;
        }

        try {
            Method callback = ClassUtils.findDeclaredMethod(binding.getBean().getClass(),
                    callbackName, String.class, Object.class, Object.class);
            if (callback == null) {
                log.warn("未找到回调方法: {}.{}", binding.getBeanName(), callbackName);
                return;
            }
            ClassUtils.setAccessible(callback);
            ReflectUtils.invoke(binding.getBean(), callback.getName(), callback.getReturnType(), callback.getParameterTypes(),
                binding.getConfigKey(), oldValue, newValue);
        } catch (Exception e) {
            log.error("调用回调方法失败: {}.{}", binding.getBeanName(), callbackName, e);
        }
    }

    /**
     * 解析后的表达式结果，包含配置键和默认值。
     */
    private static class ParsedExpression {
        final String key;
        final String defaultValue;

        ParsedExpression(String key, String defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }
    }
}
