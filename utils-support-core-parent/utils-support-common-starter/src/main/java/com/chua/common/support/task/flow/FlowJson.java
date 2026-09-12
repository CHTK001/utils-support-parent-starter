package com.chua.common.support.task.flow;

import com.chua.common.support.lang.json.Json;

/**
 * 流程定义 JSON 导入导出工具。
 *
 * <p>基于 common-starter 的 JSON 工具类实现流程定义的序列化与反序列化，
   * 复用已有工具避免重复造轮子。导出格式与前端 re流 画布数据完全一致，
 * 支持双向互通。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FlowJson {

    /**
     * 私有构造方法，禁止实例化工具类。
     */
    private FlowJson() {
    }

    /**
     * 将流程定义序列化为 JSON 字符串。
     *
     * @param definition 流程定义
     * @return JSON 字符串
     */
    public static String toJson(FlowDefinition definition) {
        return Json.toJson(definition);
    }

    /**
     * 将 JSON 字符串反序列化为流程定义。
     *
     * @param json JSON 字符串
     * @return 流程定义实例
     */
    public static FlowDefinition fromJson(String json) {
        return Json.fromJson(json, FlowDefinition.class);
    }
}
