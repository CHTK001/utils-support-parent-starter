package com.chua.common.support.task.flow;

/**
 * HTTP 调用节点接口。
 *
 * <p>发起 HTTP 请求，响应结果写入流程上下文，供下游节点消费。
   * 支持 获取 / POST / 放入 / 删除 等常用方法及自定义请求头。</p>
 *
 * <p>节点属性说明：</p>
 * <ul>
 *   <li>{@code url} — 请求地址（必填）</li>
 *   <li>{@code method} — 请求方法，默认 GET</li>
 *   <li>{@code headers} — 请求头映射（可选）</li>
 *   <li>{@code body} — 请求体（可选，用于 POST/PUT）</li>
 * </ul>
 *
 * <p>执行后写入上下文：</p>
 * <ul>
 *   <li>{@code http.result} — 响应体字符串</li>
 *   <li>{@code http.status} — 响应状态码</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface HttpCallNode extends FlowNode {

    @Override
    default String type() {
        return "httpCall";
    }
}
