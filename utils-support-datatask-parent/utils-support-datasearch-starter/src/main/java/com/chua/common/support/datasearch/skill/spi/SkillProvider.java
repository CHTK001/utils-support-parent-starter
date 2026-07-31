package com.chua.common.support.datasearch.skill.spi;

import com.chua.common.support.ai.skill.SkillDefinition;

import java.util.Collections;
import java.util.List;

/**
 * Skills 技能提供者接口
 *
 * @author CH
 */
public interface SkillProvider {

    /**
     * 获取提供者名称
     *
     * @return 提供者名称
     */
    String name();

    /**
     * 获取该提供者注册的全部技能定义
     *
     * @return 技能定义列表
     */
    List<SkillDefinition> getSkills();

    /**
     * 安装技能到指定客户端
     *
     * @param clientId 客户端标识（如编辑器名称）
     * @param skillId  技能标识
     * @return 是否安装成功
     */
    default boolean install(String clientId, String skillId) {
        return false;
    }

    /**
     * 从指定客户端卸载技能
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 是否卸载成功
     */
    default boolean uninstall(String clientId, String skillId) {
        return false;
    }

    /**
     * 列出当前机器上已安装（配置目录存在）的可用客户端。
     *
     * @return 配置目录实际存在的客户端名称列表
     */
    default List<String> listAvailable() {
        return Collections.emptyList();
    }
}
