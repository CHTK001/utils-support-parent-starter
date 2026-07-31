package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.datasearch.skill.spi.SkillProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * datasearch Skill 提供器，负责将数据搜索能力以 Skill 方式注册并提供安装/卸载服务。
 * <p>
 * 继承自 {@link AgentEditorProvider}，仅实现 {@link SkillProvider} 接口，
 * 提供技能定义、安装、卸载与安装状态查询能力。
 * </p>
 *
 * @author yemin
 * @since 1.2.0
 */
@Spi("datasearch")
@Slf4j
public class AgentEditorSkillProvider extends AgentEditorProvider implements SkillProvider {

    @Override
    public String name() {
        return "datasearch";
    }

    /**
     * 获取 datasearch 提供的全部技能定义列表，包含视频搜索、视频下载、音乐搜索、
     * 歌单详情、歌曲详情、全量搜索 6 个技能。
     *
     * @return 技能定义不可变列表
     */
    @Override
    public List<SkillDefinition> getSkills() {
        return listSkills();
    }

    /**
     * 安装 datasearch Skill 到指定客户端对应的 AI 编辑器，使用 STDIO 传输模式。
     *
     * @param clientId 客户端标识，可为编辑器名称（如 Cursor）或配置目录（如 .cursor）
     * @param skillId  技能标识
     * @return 安装成功返回 true；客户端不存在或安装失败返回 false
     */
    @Override
    public boolean install(String clientId, String skillId) {
        log.info("datasearch Skill 安装: clientId={}, skillId={}", clientId, skillId);
        AgentEditor editor = findEditor(clientId);
        if (editor == null) {
            log.warn("未找到客户端: {}", clientId);
            return false;
        }
        if (editor == AgentEditor.TRAE_CN) {
            log.info("TRAE-CN 不支持 MCP 安装，跳过");
            return true;
        }
        return installTo(editor, McpMode.STDIO);
    }

    /**
     * 从指定客户端对应的 AI 编辑器中卸载 datasearch Skill。
     *
     * @param clientId 客户端标识，可为编辑器名称或配置目录
     * @param skillId  技能标识
     * @return 卸载成功返回 true；客户端不存在或卸载失败返回 false
     */
    @Override
    public boolean uninstall(String clientId, String skillId) {
        log.info("datasearch Skill 卸载: clientId={}, skillId={}", clientId, skillId);
        AgentEditor editor = findEditor(clientId);
        if (editor == null) {
            log.warn("未找到客户端: {}", clientId);
            return false;
        }
        if (editor == AgentEditor.TRAE_CN) {
            log.info("TRAE-CN 不支持 MCP 卸载，跳过");
            return true;
        }
        return uninstallFrom(editor);
    }

    /**
     * 列出所有受支持编辑器上 datasearch Skill 的安装状态。
     *
     * @return 编辑器名称到安装状态的映射
     */
    public Map<String, Boolean> listInstalled() {
        return super.listInstalled();
    }

    /**
     * 列出当前机器上已安装（配置目录存在）的可用 Skill 客户端。
     *
     * @return 配置目录实际存在的编辑器名称列表
     */
    @Override
    public List<String> listAvailable() {
        return listAvailableEditors();
    }
}
