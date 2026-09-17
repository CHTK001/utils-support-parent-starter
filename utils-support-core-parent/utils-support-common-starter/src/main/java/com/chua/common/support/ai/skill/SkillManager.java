package com.chua.common.support.ai.skill;

import java.util.List;
import java.util.Map;

/**
* 技能管理器接口
*
* <p>集中管理所有注册的技能定义，支持技能注册、发现和执行路由。
*
* @author CH
* @since 2026/07/15
 */
public interface SkillManager {

    /**
    * 注册技能
    *
    * @param skillDefinition 技能定义
    * @return 当前管理器，支持链式调用
    */
    SkillManager register(SkillDefinition skillDefinition);

    /**
    * 获取指定名称的技能定义
    *
    * @param name 技能名称
    * @return 技能定义，未找到返回 null
    */
    SkillDefinition get(String name);

    /**
    * 获取所有已注册的技能定义
    *
    * @return 名称到技能定义的映射
    */
    Map<String, SkillDefinition> getAll();

    /**
    * 获取所有技能的工具描述列表
    *
    * @return 技能的工具描述列表
    */
    List<Map<String, Object>> listAllToolDescriptors();

    /**
    * 执行技能
    *
    * @param name 技能名称
    * @param args 调用参数
    * @return 技能执行结果
    */
    SkillResult execute(String name, Map<String, Object> args);

    /**
    * 判断是否包含指定技能
    *
    * @param name 技能名称
    * @return 是否存在
    */
    boolean contains(String name);
}
