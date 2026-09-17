package com.chua.common.support.spi;

import com.chua.common.support.collection.SortedArrayList;
import com.chua.common.support.collection.SortedList;
import com.chua.common.support.spi.autowire.ServiceAutowire;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.spi.resolver.ServiceResolver;
import com.chua.common.support.utils.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.chua.common.support.constant.NumberConstant.DEFAULT_SIZE;
import static com.chua.common.support.spi.definition.ServiceDefinition.COMPARATOR;

/**
 * 服务提供者注册表
 * <p>
 *     管理服务定义的注册、注销和查询
 * </p>
 *
 * @author CH
 * @since 2024-12-05
*/
class ServiceProviderRegistry {
    
    /**
    * 服务定义映射表
    */
    private final Map<String, SortedList<ServiceDefinition>> definitions;
    
    /**
    * 默认服务定义列表
    */
    private final SortedList<ServiceDefinition> defaultDefinitions;
    
    /**
    * 自动装配器
    */
    private final ServiceAutowire serviceAutowire;
    
    /**
    * 构造服务提供者注册表
    *
    * @param serviceAutowire 自动装配器
    */
    public ServiceProviderRegistry(ServiceAutowire serviceAutowire) {
        this.serviceAutowire = serviceAutowire;
        this.definitions = new ConcurrentHashMap<>();
        this.defaultDefinitions = new SortedArrayList<>(COMPARATOR);
    }
    
    /**
    * 注册服务定义列表
    *
    * @param serviceDefinitions 服务定义列表
    */
    public void register(List<ServiceDefinition> serviceDefinitions) {
        for (ServiceDefinition serviceDefinition : serviceDefinitions) {
            String name = serviceDefinition.getName();
            if (StringUtils.isEmpty(name)) {
                continue;
            }
            
            definitions.computeIfAbsent(name, it -> new SortedArrayList<>(COMPARATOR)).add(serviceDefinition);
            if (serviceDefinition.isDefault()) {
                defaultDefinitions.add(serviceDefinition);
            }
        }
    }
    
    /**
    * 注册服务定义数组
    *
    * @param serviceDefinitions 服务定义数组
    */
    public void register(ServiceDefinition... serviceDefinitions) {
        register(List.of(serviceDefinitions));
    }
    
    /**
    * 通过解析器注册服务定义
    *
    * @param resolver 服务解析器
    * @param type 服务类型
    * @param classLoader 类加载器
    */
    public void register(ServiceResolver resolver, Class<?> type, ClassLoader classLoader) {
        List<ServiceDefinition> resolve = resolver.resolve(type, classLoader);
        register(resolve);
    }
    
    /**
    * 注册对象实例
    *
    * @param name 名称
    * @param ref 实例引用
    * @param type 服务类型
    */
    public void register(String name, Object ref, Class<?> type) {
        name = name.toUpperCase();
        ServiceDefinition serviceDefinition = new ServiceDefinition();
        serviceDefinition.setObj(ref);
        serviceDefinition.setType(type);
        serviceDefinition.setImplClass(ref.getClass());
        definitions.computeIfAbsent(name, it -> new SortedArrayList<>(COMPARATOR)).add(serviceDefinition);
    }
    
    /**
    * 注册实现类
    *
    * @param name 名称
    * @param ref 实现类
    * @param type 服务类型
    */
    public void register(String name, Class<?> ref, Class<?> type) {
        name = name.toUpperCase();
        ServiceDefinition serviceDefinition = new ServiceDefinition();
        serviceDefinition.setImplClass(ref);
        serviceDefinition.setType(type);
        definitions.computeIfAbsent(name, it -> new SortedArrayList<>(COMPARATOR)).add(serviceDefinition);
    }
    
    /**
    * 注销服务定义
    *
    * @param baseName 基础名称
    * @param resolverType 解析器类型
    */
    public void unregister(String baseName, Class<? extends ServiceResolver> resolverType) {
        Map<String, List<ServiceDefinition>> remove = new HashMap<>(DEFAULT_SIZE);
        for (Map.Entry<String, SortedList<ServiceDefinition>> entry : definitions.entrySet()) {
            SortedList<ServiceDefinition> value = entry.getValue();
            for (ServiceDefinition serviceDefinition : value) {
                doRegisterRemoveCollection(remove, baseName, resolverType, serviceDefinition, entry.getKey());
            }
        }
        
        if (remove.isEmpty()) {
            return;
        }
        
        for (Map.Entry<String, List<ServiceDefinition>> entry : remove.entrySet()) {
            definitions.get(entry.getKey()).removeAll(entry.getValue());
        }
    }
    
    /**
    * 收集待移除的服务定义
    */
    private void doRegisterRemoveCollection(Map<String, List<ServiceDefinition>> remove, String baseName, 
                                             Class<? extends ServiceResolver> resolverType, 
                                             ServiceDefinition serviceDefinition, String key) {
        Class<?> finderType = serviceDefinition.getFinderType();
        if (null != finderType && resolverType.isAssignableFrom(finderType)) {
            doRegisterRemoveCollectionItem(baseName, key, remove, serviceDefinition);
        }
    }
    
    /**
    * 添加到待移除集合
    */
    private void doRegisterRemoveCollectionItem(String baseName, String key, 
                                                Map<String, List<ServiceDefinition>> remove, 
                                                ServiceDefinition serviceDefinition) {
        if (StringUtils.isBlank(baseName)) {
            remove.computeIfAbsent(key, it -> new LinkedList<>()).add(serviceDefinition);
        }
        if (baseName.equalsIgnoreCase(serviceDefinition.getName())) {
            remove.computeIfAbsent(key, it -> new LinkedList<>()).add(serviceDefinition);
        }
    }
    
    /**
    * 获取所有服务定义
    *
    * @return 服务定义映射
    */
    public Map<String, SortedList<ServiceDefinition>> getDefinitions() {
        return definitions;
    }
    
    /**
    * 获取默认服务定义列表
    *
    * @return 默认服务定义列表
    */
    public SortedList<ServiceDefinition> getDefaultDefinitions() {
        return defaultDefinitions;
    }
    
    /**
    * 判断是否为空
    *
    * @return true 表示为空
    */
    public boolean isEmpty() {
        return definitions.isEmpty();
    }
    
    /**
    * 获取所有扩展名称
    *
    * @return 扩展名称集合
    */
    public Set<String> getExtensionNames() {
        return definitions.keySet();
    }
}
