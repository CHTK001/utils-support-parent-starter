package com.chua.common.support.task.message;

import java.util.Map;

/**
 * 模板信息
 *
 * <p>描述一个消息模板的元数据，包括 ID、名称、内容、参数定义等。
 *
 * @param id        模板 ID
 * @param name      模板名称
 * @param content   模板内容（支持 {{param}} 占位符）
 * @param type      模板类型（email/sms/notification 等）
 * @param paramDefs 参数定义（参数名 → 说明）
 * @author CH
 * @since 2026/07/17
 */
public record TemplateInfo(String id, String name, String content, String type, Map<String, String> paramDefs) {

    /**
     * 使用参数填充模板内容
     *
     * @param params 参数映射
     * @return 填充后的模板内容
     */
    public String render(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return content;
        }
        String result = content;
        for (var entry : params.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}
