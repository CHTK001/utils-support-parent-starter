package com.chua.common.support.task.flow;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.definition.ServiceDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程节点类型注册表。
 *
 * <p>通过 SPI 机制发现全部 {@link FlowNodeExecutor} 实现，
 * 汇总节点类型清单并负责按类型实例化节点执行器。
 * 各领域模块（如爬虫）只需实现接口并注册 SPI，即可被流程编排引擎发现，
 * 核心模块无需任何改动，实现"节点插件式扩展"。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FlowNodeRegistry {

    /**
     * 私有构造方法，禁止实例化工具类。
     */
    private FlowNodeRegistry() {
    }

    /**
     * 获取全部节点类型元信息。
     *
     * <p>遍历 SPI 注册的节点执行器，读取 {@link FlowNode} 注解与 SPI 描述
     * 汇总为元信息列表，供前端属性面板渲染节点类型。</p>
     *
     * @return 节点类型元信息列表
     */
    public static List<FlowNodeMetadata> listMetadata() {
        List<FlowNodeMetadata> result = new ArrayList<>();
        Set<String> extensions = ServiceProvider.of(FlowNodeExecutor.class).getExtensions();
        for (String extension : extensions) {
            FlowNodeExecutor executor = ServiceProvider.of(FlowNodeExecutor.class).getExtension(extension);
            if (executor == null) {
                continue;
            }
            result.add(buildMetadata(executor, extension));
        }
        return result;
    }

    /**
     * 按类型标识获取节点执行器实例。
     *
     * <p>每次调用返回新实例，避免节点状态在多流程实例间共享。</p>
     *
     * @param type 节点类型标识
     * @return 节点执行器实例，未注册时返回 null
     */
    public static FlowNodeExecutor getExecutor(String type) {
        return ServiceProvider.of(FlowNodeExecutor.class).getNewExtension(type);
    }

    /**
     * 判断节点类型是否已注册。
     *
     * @param type 节点类型标识
     * @return 已注册返回 true，否则返回 false
     */
    public static boolean exists(String type) {
        return ServiceProvider.of(FlowNodeExecutor.class).getExtension(type) != null;
    }

    /**
     * 获取全部已注册节点类型标识。
     *
     * @return 节点类型标识集合
     */
    public static Set<String> listTypes() {
        return ServiceProvider.of(FlowNodeExecutor.class).getExtensions();
    }

    /**
     * 构建单个节点的类型元信息。
     *
     * <p>优先读取 {@link FlowNode} 注解声明的类型标识与描述，
     * 无注解时回退使用 SPI 扩展名与 SPI 描述。</p>
     *
     * @param executor  节点执行器实例
     * @param extension SPI 扩展名
     * @return 节点类型元信息
     */
    private static FlowNodeMetadata buildMetadata(FlowNodeExecutor executor, String extension) {
        FlowNode annotation = executor.getClass().getAnnotation(FlowNode.class);
        if (annotation == null) {
            ServiceDefinition definition = findDefinition(extension);
            String describe = definition != null ? definition.getDescribe() : "";
            return new FlowNodeMetadata(extension, extension, describe);
        }
        return new FlowNodeMetadata(annotation.value(), annotation.value(), annotation.describe());
    }

    /**
     * 按扩展名查找 SPI 定义信息。
     *
     * @param extension 扩展名
     * @return SPI 定义，不存在时返回 null
     */
    private static ServiceDefinition findDefinition(String extension) {
        var definitions = ServiceProvider.of(FlowNodeExecutor.class).getDefinitions(extension);
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }
        return definitions.get(0);
    }

    /**
     * 获取节点类型到描述的映射。
     *
     * @return 类型标识到描述的映射
     */
    public static Map<String, String> describeMap() {
        Map<String, String> result = new LinkedHashMap<>();
        for (FlowNodeMetadata metadata : listMetadata()) {
            result.put(metadata.type(), metadata.describe());
        }
        return Collections.unmodifiableMap(result);
    }
}
