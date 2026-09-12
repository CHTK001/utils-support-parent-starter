package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
* datasearch MCP 提供器，负责将数据搜索服务以 MCP 协议方式安装到 AI 编辑器。
* <p>
* 继承自 {@link AgentEditorProvider}，仅实现 {@link McpProvider} 接口，
* 提供 MCP 客户端的创建、安装、卸载与安装状态查询能力。
* </p>
*
* @author yemin
* @author CH
* @since 4.0.0.42
 */
@Spi("datasearch")
@Slf4j
public class AgentEditorMcpProvider extends AgentEditorProvider implements McpProvider {

    /**
    * 获取 MCP 提供器名称。
    *
    * @return 提供器名称字符串，固定为 {@link #NAME}
     */
    @Override
    public String name() {
        return NAME;
    }

    /**
    * 创建 datasearch MCP 客户端实例，每次调用返回新实例。
    *
    * @return 新的 {@link DatasearchMcpClient} 实例
     */
    @Override
    public McpClient create() {
        return new DatasearchMcpClient();
    }

    /**
    * 将 datasearch MCP 安装到指定客户端对应的 AI 编辑器，使用 STDIO 传输模式。
    *
    * @param clientId 客户端标识，可为编辑器名称（如 Cursor）或配置目录（如 .Cursor）
    * @param toolId   工具标识
    * @return 安装成功返回 true；客户端不存在或安装失败返回 false
     */
    @Override
    public boolean install(String clientId, String toolId) {
        log.info("datasearch MCP 安装: clientId={}, toolId={}", clientId, toolId);
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
    * 从指定客户端对应的 AI 编辑器中卸载 datasearch MCP。
    *
    * @param clientId 客户端标识，可为编辑器名称或配置目录
    * @param toolId   工具标识
    * @return 卸载成功返回 true；客户端不存在或卸载失败返回 false
     */
    @Override
    public boolean uninstall(String clientId, String toolId) {
        log.info("datasearch MCP 卸载: clientId={}, toolId={}", clientId, toolId);
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
    * 列出所有受支持编辑器上 datasearch MCP 的安装状态。
    *
    * @return 编辑器名称到安装状态的映射
     */
    public Map<String, Boolean> listInstalled() {
        return super.listInstalled();
    }

    /**
    * 列出当前机器上已安装（配置目录存在）的可用 MCP 客户端。
    *
    * @return 配置目录实际存在的编辑器名称列表
     */
    @Override
    public List<String> listAvailable() {
        return listAvailableEditors();
    }
}
