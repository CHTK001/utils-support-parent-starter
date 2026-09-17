package com.chua.common.support.ai.mcp;

import java.util.Collections;
import java.util.List;

/**
* MCP 提供者接口
*
* <p>通过 SPI 机制创建 {@link McpClient} 实例，按名称注册 MCP 服务端实现。
*
* @author CH
* @since 2026/07/15
 */
public interface McpProvider {

    /**
    * 获取提供者名称
    *
    * @return 提供者名称
    */
    String name();

    /**
    * 创建 MCP 客户端
    *
    * @return McpClient 实例
    */
    McpClient create();

    /**
    * 安装 MCP 服务到指定客户端
    *
    * @param clientId 客户端标识（如编辑器名称）
    * @param toolId   工具标识（特定工具名称，为空表示安装全部）
    * @return 是否安装成功
    */
    default boolean install(String clientId, String toolId) {
        return false;
    }

    /**
    * 卸载 MCP 服务从指定客户端
    *
    * @param clientId 客户端标识
    * @param toolId   工具标识（特定工具名称，为空表示卸载全部）
    * @return 是否卸载成功
    */
    default boolean uninstall(String clientId, String toolId) {
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
