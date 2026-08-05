package com.chua.common.support.spi.definition;

import com.chua.common.support.spi.annotations.*;
import com.chua.common.support.utils.*;

import java.net.URL;
import java.util.*;

import static com.chua.common.support.constant.ContextConstant.COMPONENT;
import static com.chua.common.support.constant.ValueConstant.SYMBOL_EMPTY_STRING_ARRAY;


/**
 * SPI 服务定义构建工具类，用于根据注解信息生成 {@link ServiceDefinition} 实例。
 * <p>
 * 该类会读取实现类上的 {@link Spi}、{@link SpiDescribe}、{@link SpiSupport}、{@link SpiOrder} 等注解，
 * 组装服务名称、描述信息、优先级、默认实现标识以及关联的扩展点信息，供 SPI 解析器统一使用。
 *
 * @author CH
 */
public class ServiceDefinitionUtils {

    /**
     * 根据服务类型和实现类构建对应的服务定义列表。
     *
     * @param service 服务扩展点类型
     * @param implType 服务实现类
     * @param resolverType 发现该服务定义的解析器类型
     * @return 构建得到的服务定义列表
     */
    public static List<ServiceDefinition> buildDefinition(Class<?> service, Class<?> implType, Class<?> resolverType) {
        return buildDefinition(service, resolverType, null, implType, null, null);
    }

    /**
     * 根据服务名称、服务类型和实例对象构建服务定义列表。
     *
     * @param name 服务名称
     * @param service 服务扩展点类型
     * @param obj 服务实例对象
     * @param resolverType 发现该服务定义的解析器类型
     * @return 构建得到的服务定义列表
     */
    public static List<ServiceDefinition> buildDefinition(String name, Class<?> service, Object obj, Class<?> resolverType) {
        Class<?> aClass = ClassUtils.toType(obj);
        List<ServiceDefinition> serviceDefinitions = buildDefinition(service, resolverType, obj, aClass, null, null);
        if (CollectionUtils.isEmpty(serviceDefinitions)) {
            return buildDefinition(service, resolverType, obj, aClass,
                StringUtils.defaultString(name, aClass.getTypeName()), null);
        }
        return serviceDefinitions;
    }

    /**
     * 根据服务类型和对象实例构建服务定义列表，名称会从对象类型自动推导。
     *
     * @param service 服务扩展点类型
     * @param obj 服务实例对象
     * @param resolverType 发现该服务定义的解析器类型
     * @return 构建得到的服务定义列表
     */
    public static List<ServiceDefinition> buildDefinition(Class<?> service, Object obj, Class<?> resolverType) {
        Class<?> aClass = ClassUtils.toType(obj);
        return buildDefinition(aClass.getSimpleName().replace(service.getSimpleName(), ""), service, obj, resolverType);
    }

    /**
     * 构建服务定义列表，支持枚举类型、别名和 URL 来源的处理。
     *
     * @param service 服务扩展点类型
     * @param resolverType 发现该服务定义的解析器类型
     * @param obj 服务实例对象
     * @param implType 服务实现类
     * @param alias 服务别名
     * @param url 服务定义来源地址
     * @return 构建得到的服务定义列表
     */
    public static List<ServiceDefinition> buildDefinition(Class<?> service, Class<?> resolverType, Object obj, Class<?> implType, String alias, URL url) {
        if (null == implType) {
            implType = ClassUtils.toType(obj);
        }

        if (implType.getDeclaredAnnotation(SpiIgnore.class) != null) {
            return Collections.emptyList();
        }

        if (implType.isEnum()) {
            return buildEnumDefinition(service, resolverType, obj, implType, alias, url);
        }

        List<ServiceDefinition> rs = new LinkedList<>(buildDefinitionType(service, resolverType, obj, implType, url));
        if (null == alias || alias.isEmpty() || !rs.isEmpty()) {
            return rs;
        }
        SpiOrder spiOrder = implType.getDeclaredAnnotation(SpiOrder.class);
        int orderValue = 0;
        if (null != spiOrder) {
            orderValue = spiOrder.value();
        }
        if (StringUtils.isNotEmpty(alias)) {
            rs.add(buildDefinitionAlias(service, resolverType, obj, implType, url, alias, orderValue));
        }
        return rs;
    }

    /**
     * 为枚举类型构建对应的服务定义列表。
     *
     * @param service 服务扩展点类型
     * @param resolverType 发现该服务定义的解析器类型
     * @param obj 枚举实例对象
     * @param implType 枚举实现类
     * @param alias 服务别名
     * @param url 服务定义来源地址
     * @return 枚举对应的服务定义列表
     */
    private static List<ServiceDefinition> buildEnumDefinition(Class<?> service, Class<?> resolverType, Object obj, Class<?> implType, String alias, URL url) {
        if (StringUtils.isEmpty(alias)) {
            return Collections.emptyList();
        }
        List<ServiceDefinition> rs = new LinkedList<>();
        if (service.isAssignableFrom(implType)) {
            rs.add(buildDefinitionAlias(service, resolverType, obj, implType, url, alias, 0));
        }

        Object[] enumConstants = implType.getEnumConstants();
        for (Object enumConstant : enumConstants) {
            rs.addAll(buildDefinition(service, resolverType, enumConstant, enumConstant.getClass(), alias, url));
        }

        return rs;
    }

    /**
     * 判断实现类是否满足 SPI 条件注解要求。
     *
     * @param implType 服务实现类
     * @return 满足条件则返回 {@code true}
     */
    private static boolean isCondition(Class<?> implType) {
        SpiIgnore spiIgnore = implType.getDeclaredAnnotation(SpiIgnore.class);
        if (null != spiIgnore) {
            return false;
        }

        SpiCondition spiCondition = implType.getDeclaredAnnotation(SpiCondition.class);
        if (null == spiCondition) {
            return true;
        }

        String[] value = spiCondition.value();
        for (String s : value) {
            try {
                Class.forName(s);
            } catch (ClassNotFoundException e) {
                return false;
            }
        }

        Class<? extends SpiCondition.Condition>[] aClass = spiCondition.onCondition();
        for (Class<? extends SpiCondition.Condition> aClass1 : aClass) {
            SpiCondition.Condition condition = null;
            try {
                condition = ClassUtils.newInstance(aClass1);
            } catch (Exception ignored) {
            }

            if (null != condition && !condition.isCondition()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 根据实现类和注解信息构造服务定义集合。
     *
     * @param service 服务扩展点类型
     * @param resolverType 发现该服务定义的解析器类型
     * @param obj 服务实例对象
     * @param implType 服务实现类
     * @param url 服务定义来源地址
     * @return 构造得到的服务定义集合
     */
    private static Collection<? extends ServiceDefinition> buildDefinitionType(Class<?> service, Class<?> resolverType, Object obj, Class<?> implType, URL url) {
        if (!isCondition(implType)) {
            return Collections.emptyList();
        }

        String[] name = getName(implType);
        if (name.length == 0) {
            return Collections.emptyList();
        }
        List<ServiceDefinition> rs = new LinkedList<>();
        url = url == null ? implType.getProtectionDomain().getCodeSource().getLocation() : url;
        int order = getOrder(implType);
        for (String s : name) {
            if (StringUtils.isBlank(s)) {
                continue;
            }
            rs.add(buildDefinitionAlias(service, resolverType, obj, implType, url, s, order));
        }

        return rs;
    }

    /**
     * 从实现类注解中提取服务名称集合。
     *
     * @param implType 服务实现类
     * @return 服务名称数组
     */
    private static String[] getName(Class<?> implType) {
        if(null == implType) {
            return SYMBOL_EMPTY_STRING_ARRAY;
        }
        Set<String> name = new LinkedHashSet<>();
        Spi spi = implType.getDeclaredAnnotation(Spi.class);
        if (null != spi) {
            name.addAll(Arrays.asList(spi.value()));
        }
        Extension extension = implType.getDeclaredAnnotation(Extension.class);
        if (null != extension) {
            name.add(extension.value());
        }

        if (null != COMPONENT) {
            Object value = AnnotationUtils.getAnnotationAttributes(implType, COMPONENT).get("value");
            if (null != value) {
                name.add(value.toString());
            }
        }

        return name.toArray(new String[0]);
    }

    /**
     * 根据服务扩展点、实现类和别名构建单个服务定义对象。
     *
     * @param service 服务扩展点类型
     * @param resolverType 发现该服务定义的解析器类型
     * @param obj 服务实例对象
     * @param implType 服务实现类
     * @param url 服务定义来源地址
     * @param alias 服务别名
     * @param order 服务优先级
     * @return 构建得到的服务定义对象
     */
    @SuppressWarnings("ALL")
    public static ServiceDefinition buildDefinitionAlias(Class<?> service, Class<?> resolverType, Object obj, Class<?> implType, URL url, String alias, int order) {
        ServiceDefinition serviceDefinition = new ServiceDefinition();
        serviceDefinition.setClassLoader(implType.getClassLoader());
        serviceDefinition.setLoadTime(System.currentTimeMillis());
        serviceDefinition.setOrder(order == 0 ? getOrder(implType) : order);
        SpiDescribe spiDescribe = implType.getDeclaredAnnotation(SpiDescribe.class);
        if (null != spiDescribe) {
            serviceDefinition.setDescribe(spiDescribe.value());
            serviceDefinition.setDescribeType(spiDescribe.type());
            serviceDefinition.setDescribeDetail(spiDescribe.desc());
            Map<String, String> optional = new LinkedHashMap<>();
            List<DescribeOptional> optionalList = new LinkedList<>();
            for (SpiParam s : spiDescribe.optional()) {
                optionalList.add(new DescribeOptional(s.value(), s.defaultValue(), s.desc(), s.type()));
            }

            serviceDefinition.setDescribeOptional(optionalList);
        }
        SpiSupport spiSupport = implType.getDeclaredAnnotation(SpiSupport.class);
        if (null != spiSupport) {
            serviceDefinition.setSupportedTypes(ArrayUtils.toUpperCase(spiSupport.value()));
        }
        serviceDefinition.setName(alias.toUpperCase());
        serviceDefinition.setObj(obj);
        serviceDefinition.setFinderType(resolverType);
        serviceDefinition.setImplClass(implType);
        serviceDefinition.setUrl(url);
        serviceDefinition.setType(service);
        serviceDefinition.setDefault(implType.isAnnotationPresent(SpiDefault.class));

        return serviceDefinition;
    }

    /**
     * 从实现类注解中提取服务优先级。
     *
     * @param implType 服务实现类
     * @return 服务优先级，未配置时返回 0
     */
    private static int getOrder(Class<?> implType) {
        Spi spi = implType.getDeclaredAnnotation(Spi.class);
        if (null != spi) {
            return spi.order();
        }

        return 0;
    }

}
