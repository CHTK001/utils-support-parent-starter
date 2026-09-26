package com.chua.common.support.task.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 流程节点类型元信息。
 *
 * <p>描述一个已注册的节点类型，供后端节点类型清单接口与前端属性面板渲染使用。
 * 由 {@link FlowNodeRegistry} 从节点原型实例 {@link FlowNode#configSchema()} 汇总生成。</p>
 *
 * <p>{@code schema} 为该节点的配置表单元信息列表，前端据此动态渲染属性编辑表单；
 * 未提供配置字段时为空列表。</p>
 *
 * @param type     节点类型标识，如 "蜘蛛"、"httpcall"
 * @param name     节点类型名称，默认取类型标识
 * @param describe 节点类型功能描述
 * @param schema   节点配置表单元信息列表，可为空
 * @author CH
 * @since 4.0.0.42
 */
public record FlowNodeMetadata(
        String type,
        String name,
        String describe,
        List<FlowNodeField> schema
) {

    /**
     * 规范构造器：节点类型标识为空值敌对，配置字段列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * 本类型随节点类型清单下发给前端并可由 JSON 反序列化，
     * 保留 {@code schema} 的 null 语义；元素为 {@link FlowNodeField}，
     * 不会是 null，故采用可空安全的 unmodifiable 包装。</p>
     *
     * @param type     节点类型标识
     * @param name     节点类型名称
     * @param describe 节点类型功能描述
     * @param schema   节点配置表单元信息列表，可为 null
     */
    public FlowNodeMetadata {
        type = Objects.requireNonNull(type, "type 不能为 null");
        schema = schema == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(schema));
    }

    /**
     * 以空配置字段构建节点元信息。
     *
     * <p>兼容未提供 {@link FlowNode#configSchema()} 的老节点类型。</p>
     *
     * @param type     节点类型标识
     * @param name     节点类型名称
     * @param describe 节点类型功能描述
     * @return 节点元信息实例
     */
    public FlowNodeMetadata(String type, String name, String describe) {
        this(type, name, describe, new ArrayList<>());
    }
}
