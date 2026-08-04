package com.chua.common.support.task.flow;

/**
 * 流程节点类型元信息。
 *
 * <p>描述一个已注册的节点类型，供后端节点类型清单接口与前端属性面板渲染使用。
 * 由 {@link FlowNodeRegistry} 从程序式注册信息中汇总生成。</p>
 *
 * @param type     节点类型标识，如 "spider"、"httpCall"
 * @param name     节点类型名称，默认取类型标识
 * @param describe 节点类型功能描述
 * @author CH
 * @since 4.0.0.42
 */
public record FlowNodeMetadata(
        String type,
        String name,
        String describe
) {
}
