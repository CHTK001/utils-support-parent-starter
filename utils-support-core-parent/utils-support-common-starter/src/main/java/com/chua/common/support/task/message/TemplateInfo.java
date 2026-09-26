package com.chua.common.support.task.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 模板信息
 *
 * <p>描述一个消息模板的元数据，包括 ID、名称、内容、参数定义等。
 *
 * @param id        模板 标识
 * @param name      模板名称
 * @param content   模板内容（支持 {{参数}} 占位符）
 * @param type      模板类型（email/sms/notification 等）
 * @param paramDefs 参数定义（参数名 → 说明）
 * @author CH
 * @since 2026/07/17
 * @return template信息的结果
 */
public record TemplateInfo(String id, String name, String content, String type, Map<String, String> paramDefs) {

    /**
     * 规范构造器：模板标识与内容为空值敌对，参数定义做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * 已知构造点（{@code AlibabaSmsMessagePush} 查询云端模板列表）
     * 显式传 {@code paramDefs = null}，说明云端模板不带参数定义是正常状态，
     * 故保留 null 语义；参数说明取自云端，可能为 null，
     * 采用可空安全的 unmodifiable 包装而非 {@link Map#copyOf}。</p>
     *
     * <p>{@link #render(Map)} 与 {@code MessagePush#sendTemplate} 都无条件解引用
     * {@code content}，为 null 必然运行期 NPE，故提前拦截；
     * {@code name} / {@code type} 允许为 null，不校验。</p>
     *
     * @param id        模板标识
     * @param name      模板名称，可为 null
     * @param content   模板内容
     * @param type      模板类型，可为 null
     * @param paramDefs 参数定义，可为 null
     */
    public TemplateInfo {
        id = Objects.requireNonNull(id, "id 不能为 null");
        content = Objects.requireNonNull(content, "content 不能为 null");
        paramDefs = paramDefs == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(paramDefs));
    }

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
