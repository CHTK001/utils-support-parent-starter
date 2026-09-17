package com.chua.common.support.lang.template;

import java.util.LinkedHashMap;
import java.util.Map;

/**
* 模板提取得到的单个变量。
*
* <p>以“变量名 / 值 / 在输入中的路径”三个维度描述一次成功提取，
* 其中 {@link #path()} 记录该变量是从输入树的哪条键路径定位到的（与模板中的占位符键路径一致），
* 便于在不确定的输入结构下进行溯源与调试。</p>
*
* <p>同时提供 {@link #toMap()} 以 Map 形态表达该变量（键为 {@code name}/{@code value}/{@code path}），
* 便于序列化与下游以 Map 方式消费。</p>
*
* @param name  变量名，对应模板中的 {@code {变量名}} 占位符，仅由字母、数字、下划线组成
* @param value 从输入中提取到的值，保留原始类型（字符串/数字/布尔/对象/数组）
* @param path  变量在输入中的键路径（如 {@code user.address[0].city}），缺失时为空串
* @author CH
* @since 4.0.0.42
 */
public record TemplateVar(String name, Object value, String path) {

    /**
    * 以 Map 形态返回该变量。
    *
    * @return 含 name/value/path 三个键的有序映射
    */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("value", value);
        map.put("path", path);
        return map;
    }
}
