package com.chua.common.support.task.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流程节点类型注册表。
 *
 * <p>程序式节点注册表，通过 {@link #register(String, FlowNode, String)} 登记节点
 * 类型的原型实例与描述信息，供以下场景使用：</p>
 * <ul>
 *   <li>节点类型清单查询：{@link #listMetadata()}，供前端属性面板渲染节点类型</li>
 *   <li>JSON 图定义导入：{@link #createNode(String)} 按类型创建节点副本，
 *       保证流程间节点状态隔离</li>
 * </ul>
 *
 * <p>与旧版 SPI 注册机制不同，新架构中节点实例由用户直接 {@code new}
 * 并通过 {@link Flow#addNode(String, FlowNode)} 加入流程，
 * 本注册表仅作为"类型 → 原型"的目录，不再承担节点发现职责。</p>
 *
 * @since 4.0.0.42
 * @author CH
 */
public final class FlowNodeRegistry {

    /**
     * 节点类型标识到注册项的映射
     */
    private static final Map<String, Registration> REGISTRY = new ConcurrentHashMap<>();

    /**
     * 私有构造方法，禁止实例化工具类。
     */
    private FlowNodeRegistry() {
    }

    /**
     * 注册节点类型。
     *
     * <p>登记的节点原型仅用于类型清单展示与 JSON 导入时创建副本，
     * 不参与流程构建，节点实例仍由调用方直接提供。</p>
     *
     * @param type     节点类型标识
     * @param prototype 节点原型实例
     * @param describe  节点类型功能描述
     */
    public static void register(String type, FlowNode prototype, String describe) {
        REGISTRY.put(type, new Registration(type, type, describe, prototype));
    }

    /**
     * 注销节点类型。
     *
     * @param type 节点类型标识
     */
    public static void unregister(String type) {
        REGISTRY.remove(type);
    }

    /**
     * 判断节点类型是否已注册。
     *
     * @param type 节点类型标识
     * @return 已注册返回 true，否则返回 false
     */
    public static boolean exists(String type) {
        return REGISTRY.containsKey(type);
    }

    /**
     * 按类型创建节点副本。
     *
     * <p>通过克隆原型实例创建独立节点，避免多流程实例共享可变节点状态。
     * 原型不可克隆时返回原型本身。</p>
     *
     * @param type 节点类型标识
     * @return 节点副本，未注册时返回 空
     */
    public static FlowNode createNode(String type) {
        Registration registration = REGISTRY.get(type);
        if (registration == null) {
            return null;
        }
        return registration.prototype().cloneNode();
    }

    /**
     * 获取全部节点类型元信息。
     *
     * @return 节点类型元信息列表
     */
    public static List<FlowNodeMetadata> listMetadata() {
        List<FlowNodeMetadata> result = new ArrayList<>();
        for (Registration registration : REGISTRY.values()) {
            result.add(registration.toMetadata());
        }
        return result;
    }

    /**
     * 获取全部已注册节点类型标识。
     *
     * @return 节点类型标识集合
     */
    public static Set<String> listTypes() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    /**
     * 获取节点类型到描述的映射。
     *
     * @return 类型标识到描述的映射
     */
    public static Map<String, String> describeMap() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Registration registration : REGISTRY.values()) {
            result.put(registration.type(), registration.describe());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 节点类型注册项。
     *
     * <p>保存类型标识、名称、描述与原型实例，用于清单展示与副本创建。</p>
     *
     * @param type      节点类型标识
     * @param name      节点类型名称
     * @param describe  节点类型功能描述
     * @param prototype 节点原型实例
 * @author CH
     * @since 4.0.0.42
     */
    public record Registration(
            String type,
            String name,
            String describe,
            FlowNode prototype
    ) {

        /**
         * 转换为节点类型元信息。
         *
         * @return 节点类型元信息
         */
        public FlowNodeMetadata toMetadata() {
            return new FlowNodeMetadata(type, name, describe, prototype.configSchema());
        }
    }
}
