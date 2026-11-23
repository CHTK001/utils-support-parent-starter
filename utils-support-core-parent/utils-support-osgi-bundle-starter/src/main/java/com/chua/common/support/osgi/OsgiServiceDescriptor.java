package com.chua.common.support.osgi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OSGI 服务描述符，描述服务注册表中的一项服务登记。
 * <p>
 * 用于在没有框架上下文（{@code BundleContext}）的场景下，
 * 也能完整枚举服务注册表并稳定地标识每一项服务。
 * </p>
 *
 * <h3>优先级</h3>
 * <p>
 * 同一服务类型可被多个 bundle 重复注册，此时需要一条确定的规则选出「当前生效」的那一个。
 * 本描述符直接承载 OSGI 原生的 {@code service.ranking} 语义：
 * <b>取值越大优先级越高</b>，缺省为 0。该取值可原样写入
 * {@code BeanDefinition#setPriority(int)}（其语义同为「值越大越优先」），
 * 从而让容器既有的按优先级选优逻辑直接生效，无需在两侧各维护一套排序规则。
 * </p>
 *
 * @param typeName   服务类型全限定名
 * @param pid        服务持久化标识（{@code org.osgi.framework.service.pid}），未提供时为 {@code null}
 * @param instance   服务实例
 * @param properties 服务属性，只读视图
 * @author CH
 * @since 4.0.0.43
 */
    public record OsgiServiceDescriptor(String typeName, String pid, Object instance,
                                   Map<String, Object> properties) {

    /**
     * 规范构造器：服务属性做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * 唯一构造点传入的是 {@code readProperties} 产出的只读视图，
     * 其中 {@code objectClass} 的值为不可变列表，但其余键值取自框架
     * {@code getProperty}，理论上可能读到 null，故采用可空安全的 unmodifiable 包装。</p>
     *
     * <p>保留 null 语义：{@link #ranking()} 与 {@link #osgiServiceId()}
     * 都显式判空后返回默认值，说明属性缺失是本类型的正常状态。
     * {@code pid} 与 {@code instance} 同样允许为 null，不做空值敌对处理。</p>
     *
     * @param typeName   服务类型全限定名
     * @param pid        服务持久化标识
     * @param instance   服务实例
     * @param properties 服务属性
     */
    public OsgiServiceDescriptor {
        properties = properties == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /**
     * 服务优先级属性键。
     */
    public static final String SERVICE_RANKING = "service.ranking";


    /**
     * 服务唯一标识属性键。
     */
    public static final String SERVICE_ID = "service.id";

    /**
     * 构造服务描述符（属性为空表）。
     *
     * @param typeName 服务类型全限定名
     * @param pid      服务持久化标识
     * @param instance 服务实例
     */
    public OsgiServiceDescriptor(String typeName, String pid, Object instance) {
        this(typeName, pid, instance, Collections.emptyMap());
    }

    /**
     * 获取服务实例的类全限定名。
     *
     * @return 实例类名，实例为 {@code null} 时返回 {@code null}
     */
    public String implClassName() {
        return instance == null ? null : instance.getClass().getName();
    }

    /**
     * 获取服务优先级。
     *
     * <p>取自 {@code service.ranking} 属性；属性缺失、非数值或不可读时返回 0，
     * 即「与其他未声明优先级的服务同级」。</p>
     *
     * @return 服务优先级，值越大越优先
     */
    public long ranking() {
        if (properties == null) {
            return 0L;
        }
        Object value = properties.get(SERVICE_RANKING);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * 获取服务在 OSGI 注册表中的唯一编号。
     *
     * <p>注意与 {@link #stableId()} 区分：本方法返回框架分配的 {@code service.id}，
     * 仅在本框架实例内有意义；{@code stableId()} 才是可跨查询用作缓存键的稳定标识。</p>
     *
     * @return {@code service.id}，未声明时返回 {@code null}
     */
    public Long osgiServiceId() {
        if (properties == null) {
            return null;
        }
        Object value = properties.get(SERVICE_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 获取可用于索引与去重的稳定服务标识。
     * <p>
     * 优先使用服务自身的 {@code pid}；未提供时退化为实例标识哈希。
     * 同一服务实例在框架未重启前多次枚举会得到相同标识，
     * 因此可安全用作缓存键与 Bean 名称。
     * </p>
     *
     * @return 稳定服务标识
     */
    public String stableId() {
        if (pid != null && !pid.isBlank()) {
            return typeName + ":" + pid;
        }
        return typeName + ":" + System.identityHashCode(instance);
    }
}
