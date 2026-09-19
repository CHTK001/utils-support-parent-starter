package com.chua.common.support.ai.skill;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能参数 schema
 *
 * <p>描述技能参数的名称、类型、是否必填等信息。
 *
 * @author CH
 * @since 2026/07/15
 */
public class SkillArgumentSchema {

    /** 参数名称 */
    /**
     * 名称
     */
    private final String name;

    /** 参数描述 */
    /**
     * 描述
     */
    private final String description;

    /**
     * 参数类型（string、number、boolean、enum 等）
     */
    /**
     * 类型
     */
    private final String type;

    /** 是否必填 */
    /**
     * 是否必填
     */
    private final boolean required;

    /** 枚举值列表（仅 type=enum 时有效） */
    private final List<String> enumValues;

    /**
     * 创建 SkillArgumentSchema 实例
     * @param name name
     * @param name String
     * @param name String
     * @param required boolean
     * @param enumValues List
     * @param enumValues enumValues
     * @param description 描述，不允许为 null
     * @param type 类型，不允许为 null
     */
    public SkillArgumentSchema(String name, String description, String type, boolean required, List<String> enumValues) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.required = required;
        this.enumValues = enumValues;
    }

    /**
     * 获取Name
     * @return 结果字符串
     */
    public String getName() {
        return name;
    }

    /**
     * 获取Description
     * @return 结果字符串
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取Type
     * @return 结果字符串
     */
    public String getType() {
        return type;
    }

    /**
     * 是否Required
     * @return 是否成功（true 表示成功）
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * 获取EnumValues
     * @return 结果列表，无数据时为空列表
     */
    public List<String> getEnumValues() {
        return enumValues;
    }

    /**
     * ToMap
     * @return 结果映射，无数据时为空映射
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("description", description);
        map.put("type", type);
        map.put("required", required);
        if (enumValues != null) {
            map.put("enum", enumValues);
        }
        return map;
    }
}
