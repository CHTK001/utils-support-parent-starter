package com.chua.common.support.ai.skill;

import java.util.Map;

/**
* 技能处理器接口
*
* <p>执行具体的技能逻辑。每个技能定义对应一个处理器实现。
*
* @author CH
* @since 2026/07/15
 */
public interface SkillHandler {

    /**
    * 执行技能
    *
    * @param args 技能调用参数
    * @return 技能执行结果
     */
    SkillResult handle(Map<String, Object> args);
}