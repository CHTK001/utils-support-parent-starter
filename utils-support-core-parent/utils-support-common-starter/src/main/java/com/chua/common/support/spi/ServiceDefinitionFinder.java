package com.chua.common.support.spi;

import com.chua.common.support.collection.SortedArrayList;
import com.chua.common.support.collection.SortedList;
import com.chua.common.support.constant.NameConstant;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.spi.autowire.ServiceAutowire;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.spi.resolver.ServiceResolver;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.function.NameAware;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_COLON;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_COMMA;
import static com.chua.common.support.constant.NameConstant.DEFAULT;
import static com.chua.common.support.constant.NameConstant.METHOD_GETTER;
import static com.chua.common.support.spi.definition.ServiceDefinition.COMPARATOR;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 服务定义查找器
* <p>
*     根据名称或类型查找匹配的服务定义
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class ServiceDefinitionFinder {


    /**
    * 默认空服务定义
     */
    public static final ServiceDefinition DEFAULT_DEFINITION = new ServiceDefinition();

    /**
    * 服务定义映射
     */
    private final Map<String, SortedList<ServiceDefinition>> definitions;

    /**
    * 自动装配器
     */
    private final ServiceAutowire serviceAutowire;

    /**
    * 是否启用动态解析
     */
    private boolean dynamic = false;
    /**
    * 动态解析器列表
     */
    private List<ServiceResolver> dynamicResolvers;
    /**
    * 动态解析类型
     */
    private Class<?> dynamicType;
    /**
    * 动态解析类加载器
     */
    private ClassLoader dynamicClassLoader;

    /**
    * 构造服务定义查找器
    *
    * @param definitions 服务定义映射
    * @param serviceAutowire 自动装配器
     */
    public ServiceDefinitionFinder(Map<String, SortedList<ServiceDefinition>> definitions, 
                                   ServiceAutowire serviceAutowire) {
        this.definitions = definitions;
        this.serviceAutowire = serviceAutowire;
    }

    /**
    * 设置动态解析器（仅保留 {@link ServiceResolver#isDynamic()} 返回 true 的解析器）。
    *
    * @param resolvers 解析器列表
    * @param type 服务类型
    * @param classLoader 类加载器
     */
    public void setDynamicResolvers(List<ServiceResolver> resolvers, Class<?> type, ClassLoader classLoader) {
        List<ServiceResolver> dynamic = new ArrayList<>();
        for (ServiceResolver resolver : resolvers) {
            if (resolver.isDynamic()) {
                dynamic.add(resolver);
            }
        }
        this.dynamic = !dynamic.isEmpty();
        this.dynamicResolvers = dynamic;
        this.dynamicType = type;
        this.dynamicClassLoader = classLoader;
    }

    /**
    * 根据名称获取服务定义
    *
    * @param name 名称
    * @param args 构造参数
    * @return 服务定义
     */
    public ServiceDefinition getServiceDefinition(String name, Object... args) {
        String type = null;
        String name1 = name;
        name = name.toUpperCase();
        if (name.contains(SYMBOL_COLON)) {
            String[] split = name.split(SYMBOL_COLON, 2);
            type = split[0];
            name1 = split[1];
        }
        SortedList<ServiceDefinition> definitions = new SortedArrayList<>(COMPARATOR);
        for (Map.Entry<String, SortedList<ServiceDefinition>> entry : this.definitions.entrySet()) {
            SortedList<ServiceDefinition> entryValue = entry.getValue();
            for (ServiceDefinition serviceDefinition : entryValue) {
                if ((null == type || type.equalsIgnoreCase(serviceDefinition.getDescribeType())) 
                        && name1.equalsIgnoreCase(serviceDefinition.getName())) {
                    definitions.add(serviceDefinition);
                }
            }
        }

        if (!definitions.isEmpty()) {
            return definitions.first();
        }

        SortedList<ServiceDefinition> definitions2 = new SortedArrayList<>(COMPARATOR);
        if (null != args) {
            for (String item : name.split(SYMBOL_COMMA)) {
                SortedList<ServiceDefinition> definitions1 = getDefinitions(item, args);
                if (null == definitions1) {
                    continue;
                }
                definitions2.addAll(definitions1);
                definitions2.addAll(createNameAware(name, definitions1, args));
            }
        }

        if (definitions2.isEmpty() && dynamic && null != dynamicResolvers) {
            for (ServiceResolver resolver : dynamicResolvers) {
                List<ServiceDefinition> resolved = resolver.resolve(dynamicType, dynamicClassLoader);
                if (null == resolved) {
                    continue;
                }
                for (ServiceDefinition sd : resolved) {
                    String sdName = sd.getName();
                    if (sdName == null) {
                        continue;
                    }
                    if ((null == type || type.equalsIgnoreCase(sd.getDescribeType()))
                            && name1.equalsIgnoreCase(sdName)) {
                        return sd;
                    }
                }
            }
        }

        if (definitions2.isEmpty()) {
            if (name1.startsWith(METHOD_GETTER)) {
                return getServiceDefinition(name1.substring(METHOD_GETTER.length()), args);
            }
        }
        return definitions2.isEmpty() ? DEFAULT_DEFINITION : definitions2.first();
    }

    /**
    * 根据类型获取服务定义
    *
    * @param type 类型
    * @param args 构造参数
    * @return 服务定义
     */
    public ServiceDefinition getServiceDefinition(Class<?> type, Object[] args) {
        String name = type.getTypeName();
        for (SortedList<ServiceDefinition> value : definitions.values()) {
            for (ServiceDefinition serviceDefinition : value) {
                Object obj;
                try {
                    obj = serviceDefinition.newInstance(serviceAutowire, args);
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("[SPI] 实例化 {} 失败：{}",
                            serviceDefinition.getImplClass() != null ? serviceDefinition.getImplClass().getName() : "unknown",
                            e.getMessage());
                    }
                    continue;
                }
                if (null == obj) {
                    continue;
                }
                if (obj instanceof Class<?> clazz) {
                    if (clazz.isAssignableFrom(type)) {
                        return serviceDefinition;
                    }
                }

                String converted = Converter.convertIfNecessary(obj, String.class);
                if (converted != null && converted.equalsIgnoreCase(name)) {
                    return serviceDefinition;
                }
            }
        }

        if (dynamic && null != dynamicResolvers) {
            for (ServiceResolver resolver : dynamicResolvers) {
                List<ServiceDefinition> resolved = resolver.resolve(dynamicType, dynamicClassLoader);
                if (null == resolved) {
                    continue;
                }
                for (ServiceDefinition sd : resolved) {
                    Class<?> implClass = sd.getImplClass();
                    if (null != implClass && type.isAssignableFrom(implClass)) {
                        return sd;
                    }
                }
            }
        }

        return null;
    }

    /**
    * 根据名称获取服务定义列表
    *
    * @param name 名称
    * @param args 构造参数
    * @return 服务定义列表
     */
    public SortedList<ServiceDefinition> getDefinitions(String name, Object... args) {
        if(null == name && definitions.size() == 1) {
            return definitions.values().iterator().next();
        }
        name =  null == name ? DEFAULT : name.toUpperCase();
        SortedList<ServiceDefinition> rs = new SortedArrayList<>(COMPARATOR);

        SortedList<ServiceDefinition> serviceDefinitions = definitions.get(name);
        if (null != serviceDefinitions) {
            rs.addAll(serviceDefinitions);
        }

        for (Map.Entry<String, SortedList<ServiceDefinition>> entry : definitions.entrySet()) {
            if (name.equals(entry.getKey())) {
                continue;
            }

            SortedList<ServiceDefinition> entryValue = entry.getValue();
            if (null != args) {
                rs.addAll(createNameAware(name, entryValue, args));
            }
        }

        if (dynamic && null != dynamicResolvers) {
            for (ServiceResolver resolver : dynamicResolvers) {
                List<ServiceDefinition> resolved = resolver.resolve(dynamicType, dynamicClassLoader);
                if (null == resolved) {
                    continue;
                }
                for (ServiceDefinition sd : resolved) {
                    String sdName = sd.getName();
                    if (sdName == null) {
                        continue;
                    }
                    if (name.equalsIgnoreCase(sdName) && !containsDefinition(rs, sd)) {
                        rs.add(sd);
                    }
                }
            }
        }

        return rs;
    }

    /**
    * 判断列表中是否已包含指定服务定义
    *
    * @param list 服务定义列表
    * @param target 目标服务定义
    * @return true 表示已包含
     */
    private boolean containsDefinition(SortedList<ServiceDefinition> list, ServiceDefinition target) {
        for (ServiceDefinition sd : list) {
            if (null != sd.getName() && sd.getName().equalsIgnoreCase(target.getName())) {
                return true;
            }
            if (null != sd.getImplClass() && null != target.getImplClass()
                    && sd.getImplClass().equals(target.getImplClass())) {
                return true;
            }
        }
        return false;
    }

    /**
    * 创建 名称aware 匹配的服务定义集合
    *
    * @param name 名称
    * @param definitions 服务定义列表
    * @param args 构造参数
    * @return 匹配的服务定义集合
     */
    private <T> Collection<? extends ServiceDefinition> createNameAware(String name, 
                                                                        SortedList<ServiceDefinition> definitions, 
                                                                        Object[] args) {
        List<ServiceDefinition> rs = new ArrayList<>(definitions.size());
        for (ServiceDefinition definition : definitions) {
            if (!definition.isPresent()) {
                continue;
            }

            T obj = definition.newInstance(serviceAutowire, args);
            if (null == obj) {
                continue;
            }

            if (obj instanceof NameAware && !containsName(((NameAware) obj).named(), name)) {
                continue;
            }

            rs.add(definition);
        }
        return rs;
    }

    /**
    * 检查名称是否匹配
    *
    * @param named 名称数组
    * @param name 待匹配名称
    * @return true 表示匹配
     */
    private boolean containsName(String[] named, String name) {
        for (String s : named) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
