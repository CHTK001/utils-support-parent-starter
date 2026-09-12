package com.chua.common.support.datasearch.skill.spi;

import com.chua.common.support.ai.skill.SkillDefinition;

import java.util.Collections;
import java.util.List;

/**
* 在线技能提供者接口。
*
* <p>负责从在线技能市场（SkillsMP、ClawHub、SkillHub 等）搜索技能。
* 每个在线市场源一个实现。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface SkillOnlineProvider {

    /**
    * 获取提供者名称（市场源标识，如 skillsmp/clawhub/skillhub）。
    *
    * @return 提供者名称
     */
    String name();

    /**
    * 获取该市场源注册的全部技能定义。
    *
    * @return 技能定义列表
     */
    default List<SkillDefinition> getSkills() {
        return Collections.emptyList();
    }

    /**
    * 按关键词搜索在线技能。
    *
    * @param keyword 搜索关键词
    * @return 搜索结果技能定义列表
     */
    default List<SkillDefinition> search(String keyword) {
        return Collections.emptyList();
    }

    /**
    * 安装技能。
    *
    * @param clientId 客户端标识
    * @param skillId  技能标识
    * @return 是否安装成功
     */
    default boolean install(String clientId, String skillId) {
        return false;
    }

    /**
    * 卸载技能。
    *
    * @param clientId 客户端标识
    * @param skillId  技能标识
    * @return 是否卸载成功
     */
    default boolean uninstall(String clientId, String skillId) {
        return false;
    }
}
